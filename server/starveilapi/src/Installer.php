<?php
/**
 * 幂等安装器。
 *
 * 旧的安装逻辑写在 config.php 里，且：
 * - 用未定义变量（PHP 8 直接 500）；
 * - mysql.sql 里有 18 条 DROP TABLE，进一次配置页就清库；
 * - 把 pay.zip 与入口 php 复制到站点根，后端源码公网可下。
 *
 * 全部反转：只执行 CREATE TABLE IF NOT EXISTS、只写插件目录下的 lock、
 * 结构按版本号迁移，不碰站点根的任何文件。
 */

namespace Starveil\Api;

use think\Config;
use think\Db;

class Installer
{
    /** 与 info.ini 的 version 保持一致（升级时 upgradeIfNeeded 靠它触发迁移）。 */
    public const VERSION = '2.6.0';

    /**
     * 左侧快捷菜单的标签。
     *
     * 入口地址不写死：后台入口文件名由站长自定义（macCMS 安装完就提示改名），
     * 菜单里的地址取当前请求的脚本路径，见 [menuUrl]。
     */
    private const MENU_LABEL = '星幕 API';


    public static function install(): bool|array
    {
        $result = self::migrate();
        if ($result !== true) {
            return $result;
        }
        self::putSetting('schema_version', self::VERSION);
        self::seedDefaults();
        self::ensureSecret();
        self::registerMenu();
        self::ensureRootEntry();
        file_put_contents(self::lockFile(), date('c'));
        return true;
    }

    /**
     * 按版本号顺序执行 install/schema/v*.sql，已执行的记进 cuican_schema_version。
     *
     * 幂等：建表用 CREATE TABLE IF NOT EXISTS，v2.5.0 起的 ALTER 靠 isIgnorable()
     * 吞掉 1060（重复加列）；重复执行只是空跑。
     * 历史插件没有迁移概念，升级只能重装并清库，这里补上。
     */
    public static function migrate(bool $force = false): bool|array
    {
        $dir = dirname(__DIR__) . '/install/schema';
        $files = glob($dir . '/v*.sql') ?: [];
        sort($files, SORT_NATURAL);

        $applied = self::appliedVersions();
        foreach ($files as $file) {
            $version = basename($file, '.sql');
            if (!$force && in_array($version, $applied, true)) {
                continue;
            }
            $sql = str_ireplace('__PREFIX__', self::prefix(), (string)file_get_contents($file));
            foreach (self::split($sql) as $statement) {
                try {
                    Db::execute($statement);
                } catch (\Throwable $e) {
                    if (self::isIgnorable($e)) {
                        continue;
                    }
                    return ['code' => 0, 'msg' => '迁移 ' . $version . ' 失败：' . $e->getMessage()];
                }
            }
            self::recordVersion($version);
        }
        return true;
    }

    /** @return array<int,string> */
    private static function appliedVersions(): array
    {
        $table = self::prefix() . 'cuican_schema_version';
        try {
            $rows = Db::table($table)->column('version');
        } catch (\Throwable $e) {
            return []; // 全新安装时表还不存在
        }
        return array_map('strval', $rows ?: []);
    }

    private static function recordVersion(string $version): void
    {
        $table = self::prefix() . 'cuican_schema_version';
        if (Db::table($table)->where('version', $version)->find()) {
            return;
        }
        Db::table($table)->insert(['version' => $version, 'applied_at' => time()]);
    }

    /**
     * 卸载只删自己的表，且必须显式确认，避免后台误点一次把订单删光。
     */
    public static function uninstall(bool $confirm): bool|array
    {
        if (!$confirm) {
            return ['code' => 0, 'msg' => '卸载需要显式确认（Installer::uninstall(true)）'];
        }
        $tables = Db::query('SHOW TABLES LIKE ' . Db::quote(self::prefix() . 'cuican_%'));
        foreach ($tables as $row) {
            $table = str_replace('`', '', (string)reset($row));
            Db::execute('DROP TABLE IF EXISTS `' . $table . '`');
        }
        self::removeMenu();
        @unlink(self::lockFile());
        return true;
    }

    /**
     * 修复安装：不管版本记录，把所有 schema 再跑一遍（建表 IF NOT EXISTS，
     * 已存在的列由 isIgnorable() 忽略 1060）。
     *
     * 用于「版本表里有记录，但某张表被手工删了」——单靠 [migrate] 会跳过该版本。
     */
    public static function repair(): bool|array
    {
        $result = self::migrate(true);
        if ($result !== true) {
            return $result;
        }
        self::putSetting('schema_version', self::VERSION);
        self::seedDefaults();
        self::ensureSecret();
        self::registerMenu();
        self::ensureRootEntry();
        file_put_contents(self::lockFile(), date('c'));
        return true;
    }

    /**
     * 版本落后时补跑安装器。
     *
     * 打开后台就迁移：不能只靠 `tablesReady()`（它只查订单表，升级时表已存在），
     * 否则 v2.2.0 这种"只加新表"的迁移在页面上永远不触发。
     */
    public static function upgradeIfNeeded(): void
    {
        if (!self::installed()) {
            return;
        }
        $stored = (string)Db::table(self::prefix() . 'cuican_setting')
            ->where('k', 'schema_version')
            ->value('v');
        if ($stored === self::VERSION) {
            return;
        }
        self::install();
    }
    /**
     * 每次请求都能安全调用的自动迁移。
     *
     * 覆盖升级后不需要再跑 `tools/install.php`、也不需要去后台点禁用再启用：
     * 只要有一次请求打到插件（客户端接口或后台页面），这里就会把新版本的表/列补上。
     *
     * 代价控制：先看一个版本文件，命中就直接返回，**不查库**。
     * 只有版本号对不上时才走一次 `install()`（内部是幂等的建表 + 1060/1050 忽略）。
     * 迁移失败不写版本文件，下次请求会重试。
     */
    public static function autoMigrate(): void
    {
        $cache = self::versionFile();
        if (is_file($cache) && trim((string)@file_get_contents($cache)) === self::VERSION) {
            return;
        }
        // info.ini 属主被覆盖上传改成 root 时，禁用/启用会失败，这里顺手修回来
        self::ensureInfoWritable();
        if (!self::installed()) {
            return;
        }
        if (self::install() === true) {
            @file_put_contents($cache, self::VERSION);
        }
    }

    /** 已迁移到的版本号。放 runtime 下，跟缓存一起被清理也不会出错（清了就再迁移一次）。 */
    private static function versionFile(): string
    {
        $dir = ROOT_PATH . 'runtime';
        if (!is_dir($dir)) {
            @mkdir($dir, 0755, true);
        }
        return $dir . DIRECTORY_SEPARATOR . 'starveil_schema.version';
    }

    /**
     * 关键表是否存在；后台/工具用它在报 1146 之前先自愈。
     */
    public static function tablesReady(): bool
    {
        try {
            $rows = Db::query('SHOW TABLES LIKE ' . Db::quote(self::prefix() . 'cuican_order'));
            return !empty($rows);
        } catch (\Throwable $e) {
            return false;
        }
    }

    /**
     * 站点根入口。客户端固定请求 `/icciu_api.php/v1...`，这个文件必须在站点根。
     *
     * 内容只有一句 require，真正的引导在插件里：升级只覆盖 addons，根文件不用动。
     * 只创建、不覆盖（运营可能自己改过）。
     */
    private const ROOT_ENTRY = <<<'ENTRY'
<?php
/**
 * 星幕 API 入口：放在站点根，与 addons/ 同级。
 * 引导逻辑在 addons/starveilapi/src/entry.php，插件升级不需要替换本文件。
 */

header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');

$bootstrap = __DIR__ . '/addons/starveilapi/src/entry.php';
if (!is_file($bootstrap)) {
    http_response_code(500);
    echo json_encode(['code' => 500, 'msg' => '星幕 API 插件未安装，或 addons/starveilapi 路径不对'], JSON_UNESCAPED_UNICODE);
    exit;
}

require $bootstrap;
ENTRY;

    public static function ensureRootEntry(): void
    {
        $target = ROOT_PATH . 'icciu_api.php';
        if (is_file($target)) {
            return;
        }
        @file_put_contents($target, self::ROOT_ENTRY);
        @chmod($target, 0644);
    }

    /**
     * 把插件入口写进后台左侧「快捷菜单」。
     *
     * macCMS 的后台菜单是 `application/admin/common/auth.php` 里的硬编码数组，
     * 插件没有注入点（FastAdmin 的 auth_rule 机制在 macCMS 里没用）。官方留的扩展位
     * 是 `application/extra/quickmenu.php`：`标题,地址` 一行一个，后台「自定义菜单配置」
     * 就是编辑它。这里在启用时合并一条，重复启用不会叠加，卸载时移除。
     *
     * 不改任何核心代码，只动这个用户配置文件。
     */
    public static function registerMenu(): void
    {
        self::ensureInfoWritable();
        // 先把「自己写的行」全部剔掉，再在最前面插一条：
        // 反过来（保留自己的行再插入）每打开一次后台就会多一条，
        // 之前那次改动就是这么把左侧菜单堆成十几条「星幕 API」的。
        $lines = array_values(array_filter(
            self::quickMenuLines(),
            static fn (string $line): bool => !self::isOwnMenuLine($line),
        ));
        array_unshift($lines, self::MENU_LABEL . ',' . self::menuUrl());
        self::writeQuickMenu($lines);
    }

    /** 卸载时只摘掉自己那一条，别把运营自定义的菜单一起清了。 */
    public static function removeMenu(): void
    {
        $lines = array_values(array_filter(
            self::quickMenuLines(),
            static fn (string $line): bool => !self::isOwnMenuLine($line),
        ));
        self::writeQuickMenu($lines);
    }

    /**
     * 让 `info.ini` 回到「www 可写」的状态。
     *
     * 覆盖上传/解压时，zip 里的 `info.ini` 会带上解压者的属主（通常是 root）。
     * 而 macCMS 禁用插件时要重写它（`set_addon_info` 写 `state`），php-fpm 跑在 www 下，
     * 属于 root 的 644 文件写不进去 → 抛异常 → 后台只显示"插件操作失败"。
     * 这也是"全新上传能禁用、覆盖升级后不能"的真正区别：两种方式的文件属主不一样。
     *
     * 纯 PHP 改不了别人的属主，但**目录是可写的**：`unlink` 只校验目录权限，
     * 删掉再按原内容重建，新文件的属主就是 www，CMS 立刻能写。
     * 每次打开插件后台都会自检一次，所以覆盖升级之后不需要手工 chown。
     */
    public static function ensureInfoWritable(): void
    {
        $file = dirname(__DIR__) . '/info.ini';
        if (!is_file($file) || is_writable($file)) {
            return;
        }
        $content = @file_get_contents($file);
        if ($content === false || !@unlink($file)) {
            // 目录也不可写：这不是代码能绕开的，留给部署文档里的 chown 兜底
            return;
        }
        @file_put_contents($file, $content);
    }

    /**
     * 插件入口地址。
     *
     * 后台入口名由站长自定义（macCMS 装完就提示改），插件入口同样可能被改名，
     * 所以**任何名字都不能写死**。这里按可靠性排三档：
     *
     * 1. 当前请求就在插件目录下（站长打开了插件后台）——最准，顺手记进
     *    `runtime/starveil_entry.txt`，以后直接读；
     * 2. 没有当前请求（安装是在 macCMS 后台点「安装」触发的，`SCRIPT_NAME` 是后台入口，
     *    不是插件入口）：读上次记住的路径；
     * 3. 一次都没打开过（全新安装）：扫插件目录里的 php，排除插件类文件；
     *    只有一个候选就用它，多个才退回约定名。
     */
    private static function menuUrl(): string
    {
        $script = PHP_SAPI === 'cli' ? '' : (string)($_SERVER['SCRIPT_NAME'] ?? '');
        $dir = dirname(__DIR__);
        $folder = basename($dir);
        $inPlugin = str_contains($script, '/' . $folder . '/') && str_ends_with($script, '.php');
        if ($inPlugin) {
            self::rememberEntry($script);
            return $script;
        }
        $remembered = self::rememberedEntry();
        if ($remembered !== '') {
            return $remembered;
        }
        $candidates = [];
        foreach (glob($dir . DIRECTORY_SEPARATOR . '*.php') ?: [] as $file) {
            $base = basename($file);
            // 插件类文件（Starveilapi.php）是给 CMS 反射用的，不是入口
            if (strcasecmp($base, ucfirst($folder) . '.php') === 0) {
                continue;
            }
            $candidates[] = $base;
        }
        if (count($candidates) === 1) {
            return '/addons/' . $folder . '/' . $candidates[0];
        }
        foreach ($candidates as $base) {
            // 多个候选时才按 zip 里的约定名挑一次，纯粹是兜底
            if ($base === 'admin.php') {
                return '/addons/' . $folder . '/admin.php';
            }
        }
        return '/addons/' . $folder . '/admin.php';
    }

    /**
     * 记住真实入口路径。
     *
     * 入口改名后，安装/注册菜单这两条路径拿不到 `SCRIPT_NAME`（来自 macCMS 后台），
     * 靠"上一次从插件入口访问时记下的路径"才是唯一准确来源。
     */
    private static function rememberEntry(string $url): void
    {
        $file = self::entryFile();
        if (trim((string)@file_get_contents($file)) === $url) {
            return;
        }
        @file_put_contents($file, $url);
    }

    private static function rememberedEntry(): string
    {
        $url = trim((string)@file_get_contents(self::entryFile()));
        return str_ends_with($url, '.php') ? $url : '';
    }

    private static function entryFile(): string
    {
        $dir = ROOT_PATH . 'runtime';
        if (!is_dir($dir)) {
            @mkdir($dir, 0755, true);
        }
        return $dir . DIRECTORY_SEPARATOR . 'starveil_entry.txt';
    }

    /**
     * quickmenu 里某一行是不是本插件写的。
     *
     * 按**标签**认，不按入口路径认：入口改名后旧行也要被替换掉，
     * 否则菜单里会同时留下新旧两条。
     */
    private static function isOwnMenuLine(string $line): bool
    {
        return str_starts_with(trim($line), self::MENU_LABEL . ',');
    }

    /** @return list<string> 现有快捷菜单，优先读已加载的配置（含后台刚改过的值）。 */
    private static function quickMenuLines(): array
    {
        $configured = Config::get('quickmenu');
        if (is_array($configured)) {
            return array_values(array_map('strval', $configured));
        }
        $file = self::quickMenuFile();
        if (!is_file($file)) {
            return [];
        }
        $loaded = include $file;
        return is_array($loaded) ? array_values(array_map('strval', $loaded)) : [];
    }

    /** @param list<string> $lines */
    private static function writeQuickMenu(array $lines): void
    {
        $file = self::quickMenuFile();
        // macCMS 自带 helper；API 入口没加载 common 时退回 var_export
        if (function_exists('mac_arr2file')) {
            mac_arr2file($file, $lines);
            return;
        }
        @file_put_contents($file, "<?php
return " . var_export(array_values($lines), true) . ";
");
    }

    private static function quickMenuFile(): string
    {
        return APP_PATH . 'extra/quickmenu.php';
    }

    public static function prefix(): string
    {
        $prefix = (string)Config::get('database.prefix');
        return $prefix !== '' ? $prefix : 'mac_';
    }

    public static function lockFile(): string
    {
        return dirname(__DIR__) . '/install.lock';
    }

    public static function installed(): bool
    {
        return is_file(self::lockFile());
    }

    /** 按分号切语句；注释与空行跳过。PDO 不接受一次执行多条。 */
    private static function split(string $sql): array
    {
        $lines = preg_split("/\r\n|\r|\n/", $sql) ?: [];
        $buffer = '';
        $out = [];
        foreach ($lines as $line) {
            $trim = trim($line);
            if ($trim === '' || str_starts_with($trim, '--')) {
                continue;
            }
            $buffer .= $line . "\n";
            if (str_ends_with($trim, ';')) {
                $out[] = $buffer;
                $buffer = '';
            }
        }
        return $out;
    }

    private static function isIgnorable(\Throwable $e): bool
    {
        $message = $e->getMessage();
        // 1050/1060：重复建表、重复加列；1061/1062：重复索引名、重复索引键。
        // 迁移与 repair 都会重跑同一份 SQL，这些都属于"已经做过了"。
        return str_contains($message, '1050') || str_contains($message, 'already exists')
            || str_contains($message, '1060') || str_contains($message, 'Duplicate column')
            || str_contains($message, '1061') || str_contains($message, 'Duplicate key name')
            || str_contains($message, '1062') || str_contains($message, 'Duplicate entry');
    }

    /** 只在键不存在时写入。 */
    private static function ensureSetting(string $key, string $value): void
    {
        $table = self::prefix() . 'cuican_setting';
        if (Db::table($table)->where('k', $key)->find()) {
            return;
        }
        Db::table($table)->insert(['k' => $key, 'v' => $value]);
    }

    private static function putSetting(string $key, string $value): void
    {
        $table = self::prefix() . 'cuican_setting';
        $exists = Db::table($table)->where('k', $key)->find();
        if ($exists) {
            Db::table($table)->where('k', $key)->update(['v' => $value]);
            return;
        }
        Db::table($table)->insert(['k' => $key, 'v' => $value]);
    }

    /** 默认配置：只写"运营一定会用到"的项，不写厂商域名、密钥、应用 ID。 */
    private static function seedDefaults(): void
    {
        $defaults = [
            'app_theme_ui' => '1',
            'app_banner_style' => '1',
            'app_ui_tab_fun' => '0',
            'ui_home_data' => '3',
            'vip_group_id' => '3',
            'shortplay_type_id' => '0',
            'shortplay_search_type_id' => '0',
            'shortplay_tab_type_ids' => '',
            'member_enabled' => '1',
            'trysee_status' => '0',
            'trysee_minutes' => '6',
            'episode_points' => '0',
            'register_require_sms' => '1',
            'sms_daily_limit' => '10',
            'sms_ttl_seconds' => '300',
            'allowed_origins' => '[]',
            'search_hot' => '[]',
            'vip_plans' => '[]',
            'home_modules' => '["banner","featured","links","blocks","hot","continue","topic","live","guess"]',
            'banner_style' => '1',
            'banner_limit' => '6',
            'ad_enabled' => '0',
            'ad_platform' => 'noop',
            'ad_sdk_appid' => '',
            'tradplus_app_id' => '',
            'tradplus_splash' => '',
            'tradplus_reward' => '',
            'tradplus_feed' => '',
            'tradplus_pause' => '',
            'tradplus_banner' => '',
            'ad_slot_splash' => '',
            'ad_splash_image' => '',
            'ad_splash_url' => '',
            'ad_splash_title' => '',
            'ad_slot_feed' => '',
            'ad_feed_image' => '',
            'ad_feed_url' => '',
            'ad_feed_title' => '',
            'ad_feed_interval' => '8',
            'ad_slot_reward' => '',
            'ad_reward_image' => '',
            'ad_reward_url' => '',
            'ad_reward_title' => '',
            'ad_reward_seconds' => '5',
            // 直投激励视频：外链与上传各存一个键，播放时上传优先（见 Support\AdReward）
            'ad_reward_video' => '',
            'ad_reward_video_upload' => '',
            'ad_slot_pause' => '',
            'ad_pause_image' => '',
            'ad_pause_url' => '',
            'ad_pause_title' => '',
            'ad_pause_seconds' => '5',
            'ad_slot_banner' => '',
            'ad_banner_image' => '',
            'ad_banner_url' => '',
            'ad_banner_title' => '',
            'ad_ssv_secret' => '',
        ];
        foreach ($defaults as $key => $value) {
            // 已存在的配置不覆盖：重复安装不能把运营改过的值重置回默认
            self::ensureSetting($key, $value);
        }
    }

    /**
     * 会话签名密钥。
     *
     * 用 Base64(ANDROID_ID) 当 user_check 等于没有校验；现在用 HMAC，
     * 密钥只存在服务端，且每个站点不同。
     */
    private static function ensureSecret(): void
    {
        $table = self::prefix() . 'cuican_setting';
        if (Db::table($table)->where('k', 'session_secret')->find()) {
            return;
        }
        Db::table($table)->insert([
            'k' => 'session_secret',
            'v' => bin2hex(random_bytes(32)),
        ]);
    }
}
