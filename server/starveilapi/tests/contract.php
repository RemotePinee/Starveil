<?php
/**
 * 契约测试：后台配置 → 启动配置下发。
 *
 * 用法（站点根目录）：
 *   php addons/starveilapi/tests/contract.php
 *
 * 覆盖两条曾经「保存了但没生效」的链路：
 *   1. 广告位的跳转地址（ad_xxx_url）必须出现在 startup 的 ads 里；
 *   2. 底部菜单（bottom_tabs）写入后必须能被 startup 读回。
 *
 * 只读优先：所有被改动的设置项在结束时还原。
 */

declare(strict_types=1);

if (PHP_SAPI !== 'cli') {
    http_response_code(403);
    exit("请用命令行运行：php addons/starveilapi/tests/contract.php\n");
}

$root = dirname(__DIR__, 3) . DIRECTORY_SEPARATOR;

define('ROOT_PATH', $root);
define('APP_PATH', $root . 'application' . DIRECTORY_SEPARATOR);
define('MAC_COMM', APP_PATH . 'common' . DIRECTORY_SEPARATOR . 'common' . DIRECTORY_SEPARATOR);
define('MAC_HOME_COMM', APP_PATH . 'index' . DIRECTORY_SEPARATOR . 'common' . DIRECTORY_SEPARATOR);
define('MAC_ADMIN_COMM', APP_PATH . 'admin' . DIRECTORY_SEPARATOR . 'common' . DIRECTORY_SEPARATOR);
define('MAC_START_TIME', microtime(true));
define('BIND_MODULE', 'api');
define('ENTRANCE', 'api');
define('IN_FILE', '/icciu_api.php');

require $root . 'thinkphp' . DIRECTORY_SEPARATOR . 'base.php';
if (method_exists('\think\App', 'initCommon')) {
    \think\App::initCommon();
} else {
    (new \think\App())->initialize();
}
require $root . 'addons' . DIRECTORY_SEPARATOR . 'starveilapi' . DIRECTORY_SEPARATOR . 'src' . DIRECTORY_SEPARATOR . 'autoload.php';

use Starveil\Api\Service\Startup;
use Starveil\Api\Support\Setting;

$fail = 0;

function check(string $name, bool $ok, string $detail = ''): void
{
    if ($ok) {
        echo "  ok    {$name}\n";
        return;
    }
    $GLOBALS['fail']++;
    echo "  FAIL  {$name}" . ($detail !== '' ? " — {$detail}" : '') . "\n";
}

$cleanup = [];

// 1. 广告位跳转：五个位都必须拿到 url 字段。
//    漏了它，后台配好的落地页在所有广告位上都会失效——点了没反应。
// 接口里广告位的键名不是 splash/banner，而是 sdk_* 与 player_pause
$slots = ['splash' => 'sdk_startup', 'banner' => 'sdk_banner', 'feed' => 'sdk_informationflow', 'reward' => 'sdk_excitation', 'pause' => 'player_pause'];
foreach ($slots as $slot => $wire) {
    $key = 'ad_' . $slot . '_url';
    $cleanup[$key] = Setting::get($key, '');
    Setting::set($key, 'https://example.com/' . $slot);
}
$ads = (new Startup())->payload()['ads'] ?? [];
foreach ($slots as $slot => $wire) {
    $payload = $ads[$wire] ?? [];
    $expected = 'https://example.com/' . $slot;
    check(
        "广告 {$slot} 下发 url",
        ($payload['url'] ?? null) === $expected,
        '实际: ' . var_export($payload['url'] ?? null, true) . '，期望: ' . $expected
    );
}

// 2. 底部菜单：写进去的必须读得回来。
//    tabsAction 曾经漏掉 Setting::set('bottom_tabs')，保存后一个字段都没变。
$tabs = [
    ['id' => 'home', 'label' => '首页', 'icon' => 'home', 'visible' => true, 'order' => 1, 'mode' => 'home'],
    ['id' => 'rank', 'label' => '榜单', 'icon' => 'rank', 'visible' => true, 'order' => 2, 'mode' => 'rank'],
    ['id' => 'mine', 'label' => '我的', 'icon' => 'user', 'visible' => true, 'order' => 99, 'mode' => 'user'],
];
$cleanup['bottom_tabs'] = Setting::get('bottom_tabs', '');
Setting::set('bottom_tabs', json_encode($tabs, JSON_UNESCAPED_UNICODE));
$readBack = (new Startup())->payload()['bottom_tabs'] ?? [];
$ids = array_map(
    static fn ($tab) => (string)($tab['id'] ?? ''),
    is_array($readBack) ? $readBack : []
);
check('bottom_tabs 写入后能读回', $ids === ['home', 'rank', 'mine'], '实际: ' . implode(',', $ids));

// 还原现场：测试不能留下副作用
foreach ($cleanup as $key => $value) {
    Setting::set($key, (string)$value);
}

echo $fail === 0 ? "\n全部通过\n" : "\n失败 {$fail} 项\n";
exit($fail === 0 ? 0 : 1);
