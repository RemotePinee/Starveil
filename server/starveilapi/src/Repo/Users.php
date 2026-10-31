<?php
/**
 * 用户数据访问。
 *
 * 两个刻意的约束：
 * 1. 更新用户资料必须走 [updateAllowed]，只接受列白名单——把整个 `input()`
 * 交给 `allowField(true)`（ → ），用户可以自己改
 * `group_id` / 积分 / 状态；
 * 2. 密码校验兼容旧数据的 md5，但登录成功后立刻升级成 `password_hash`。
 */

declare(strict_types=1);

namespace Starveil\Api\Repo;

use Starveil\Api\Installer;
use think\Db;

final class Users
{
    /** 允许客户端修改的列。改这个表要评审，别顺手加 group_id。 */
    private const PROFILE_WHITELIST = ['user_nick_name', 'user_portrait'];

    /**
     * 注册用户的默认会员组。
     *
     * macCMS 装的初始数据里 `1` 是「游客」、`2` 是「默认会员」（install.sql 的 mac_group），
     * 核心 `model(User)` 注册时写的也是 2。写 1 的话 App 注册的用户全在游客组，
     * 后台按「默认会员」筛选一个都查不到。
     */
    private const DEFAULT_GROUP_ID = '2';

    public function findById(int $userId): ?array
    {
        if ($userId <= 0) {
            return null;
        }
        $row = Db::name('user')->where('user_id', $userId)->find();
        if (!$row) {
            return null;
        }
        // group_id 可能是 "2,3" 这种多组串，组名取第一个有效组
        $firstGroup = (int)(explode(',', (string)$row['group_id'])[0] ?? 0);
        $group = $firstGroup > 0 ? Db::name('group')->where('group_id', $firstGroup)->find() : null;
        $row['group_name'] = (string)($group['group_name'] ?? '');
        return $row;
    }

    public function findByUserName(string $userName): ?array
    {
        if ($userName === '') {
            return null;
        }
        $row = Db::name('user')->where('user_name', $userName)->find();
        return $row ?: null;
    }

    /** 注册。返回新用户 id；用户名重复由调用方先查。 */
    public function create(string $userName, string $plainPassword, int $inviterId): int
    {
        $now = time();
        $core = self::coreUserConfig();
        $chain = $this->inviterChain($inviterId);
        $userId = (int)Db::name('user')->insertGetId([
            'user_name' => $userName,
            'user_pwd' => $this->hashPassword($plainPassword),
            'user_nick_name' => $userName,
            // `user_phone` 是手机号列（varchar(16)）：这里以前写的是用户名，
            // 用户名超过 16 个字符必然 MySQL 1406 报 500。手机号由真正的绑定流程再写。
            'user_phone' => '',
            // macCMS 的 group_id 是 varchar（支持逗号分隔多组），默认组必须写成字符串
            'group_id' => self::DEFAULT_GROUP_ID,
            // 核心注册分支：reg_points -> user_points、reg_status -> user_status
            'user_points' => (int)($core['reg_points'] ?? 0),
            'user_status' => (int)($core['reg_status'] ?? 1),
            // 核心注册必写：`user_check` 会话校验串靠它，每日 IP 限流靠 reg_ip/reg_time
            'user_random' => bin2hex(random_bytes(16)),
            'user_reg_time' => $now,
            'user_reg_ip' => $this->clientIpLong(),
            'user_pid' => $chain[0],
            'user_pid_2' => $chain[1],
            'user_pid_3' => $chain[2],
        ]);
        $this->ensureExt($userId, $inviterId);
        return $userId;
    }

    /**
     * 三级分销链：核心注册时同时写 `user_pid` / `user_pid_2` / `user_pid_3`
     * （`model/User.php` 的绑定逻辑：pid_2 ← 上级的 pid、pid_3 ← 上级的 pid_2）。
     *
     * 只写 user_pid 的话，核心 `reward()` 里按 user_pid/2/3 算的三级佣金只有一级能算出来。
     *
     * @return array{0:int,1:int,2:int}
     */
    private function inviterChain(int $inviterId): array
    {
        if ($inviterId <= 0) {
            return [0, 0, 0];
        }
        $inviter = Db::name('user')
            ->where('user_id', $inviterId)
            ->field('user_id,user_pid,user_pid_2')
            ->find();
        if (!$inviter) {
            return [0, 0, 0];
        }
        return [
            (int)$inviter['user_id'],
            (int)($inviter['user_pid'] ?? 0),
            (int)($inviter['user_pid_2'] ?? 0),
        ];
    }


    /**
     * 核心 `user` 段配置。
     *
     * 核心注册分支的取法就是 `$config = config('maccms'); $config['user']['reg_points']`
     * （`application/common/model/User.php::register()`），这里同口径读，
     * 另外兜一层 `$GLOBALS['config']`（核心别处大量用这个全局，两种载入方式都有）。
     * 都取不到就回落核心出厂值：注册状态 1（启用）、注册赠送 0 分。
     *
     * @return array<string,mixed>
     */
    private static function coreUserConfig(): array
    {
        if (function_exists('config')) {
            $config = config('maccms.user');
            if (is_array($config)) {
                return $config;
            }
        }
        $globals = $GLOBALS['config']['user'] ?? null;
        return is_array($globals) ? $globals : [];
    }

    /** 注册 IP 存 int unsigned，口径同核心的 `mac_get_ip_long()`（它会认反代头）。 */
    private function clientIpLong(): int
    {
        if (function_exists('mac_get_ip_long')) {
            return (int)mac_get_ip_long();
        }
        return (int)sprintf('%u', ip2long((string)($_SERVER['REMOTE_ADDR'] ?? '0.0.0.0')));
    }

    /**
     * 密码哈希。
     *
     * 优先用 macCMS 自带的 `mac_hash_password_for_column`：它会根据列长度决定 bcrypt 还是 md5，
     * 与后台「会员管理」里改密码的存储形态保持一致；插件独立运行时退回 password_hash。
     */
    public function hashPassword(string $plain): string
    {
        if (function_exists('mac_hash_password_for_column')) {
            return (string)mac_hash_password_for_column($plain, 'user', 'user_pwd');
        }
        return password_hash($plain, PASSWORD_DEFAULT);
    }

    /**
     * 校验密码。返回 `true` 时若原值是 md5，顺手升级成 bcrypt（`$rehash` 为待写回的新哈希）。
     */
    public function verifyPassword(string $plain, string $stored, ?string &$rehash = null): bool
    {
        $rehash = null;
        // macCMS 的校验函数兼容 bcrypt / 两种 md5 变体 / 极少数明文，直接用它的口径
        if (function_exists('mac_verify_password')) {
            $ok = (bool)mac_verify_password($plain, $stored);
            if ($ok && !str_starts_with($stored, '$2')) {
                $rehash = $this->hashPassword($plain);
            }
            return $ok;
        }
        if (str_starts_with($stored, '$2')) {
            return password_verify($plain, $stored);
        }
        if (preg_match('/^[a-f0-9]{32}$/i', $stored) === 1) {
            if (hash_equals(strtolower($stored), md5($plain))) {
                $rehash = $this->hashPassword($plain);
                return true;
            }
        }
        return false;
    }

    /**
     * 只写 `user_pwd`。
     *
     * 登录时把旧 md5 升级成 bcrypt 也走这里，所以不在这里轮换 `user_random`：
     * 升级发生在下发会话之前，轮换只会顺手把用户的网页会话踢掉。
     * 真正的改密 / 重置密码写完密码要调 `Auth\Session::revoke()`，旧 cookie 才立刻失效。
     */
    public function updatePasswordHash(int $userId, string $hash): void
    {
        Db::name('user')->where('user_id', $userId)->update(['user_pwd' => $hash]);
    }

    /** 只更新白名单内的字段；未在白名单里的键静默忽略（不报错，避免探测哪些列存在）。 */
    public function updateAllowed(int $userId, array $input): void
    {
        $data = [];
        foreach (self::PROFILE_WHITELIST as $column) {
            if (array_key_exists($column, $input)) {
                $data[$column] = mb_substr((string)$input[$column], 0, 64);
            }
        }
        if ($data !== []) {
            Db::name('user')->where('user_id', $userId)->update($data);
        }
    }

    /**
     * 会员到期时间顺延。
     *
     * macCMS 的 `group_id` 是 varchar 且允许逗号分隔多组；这里把 VIP 组并入现有组列表，
     * 不覆盖用户已有的其它组，`user_end_time` 从"当前到期时间与现在取大"开始顺延。
     */
    public function extendMembership(int $userId, int $groupId, int $days): void
    {
        $user = Db::name('user')->where('user_id', $userId)->find();
        if (!$user) {
            return;
        }
        $groups = array_values(array_filter(
            array_map('trim', explode(',', (string)$user['group_id'])),
            // '0' 是「没组」的占位值，留在串里只会让 findById 取到空组名
            static fn (string $group): bool => $group !== '' && $group !== '0',
        ));
        if (!in_array((string)$groupId, $groups, true)) {
            $groups[] = (string)$groupId;
        }
        $base = max(time(), (int)($user['user_end_time'] ?? 0));
        $endTime = $base + max($days, 0) * 86400;
        Db::name('user')->where('user_id', $userId)->update([
            'group_id' => implode(',', $groups),
            'user_end_time' => $endTime,
        ]);
        // 开通也要进积分日志：后台的会员流水 / 分销结算都按 mac_plog 看，
        // 只改 user_end_time 的话后台根本没有这一单。钱不走积分，点数记 0。
        (new Plog())->record(
            $userId,
            Plog::TYPE_UPGRADE,
            0,
            '开通会员：' . $this->groupName($groupId) . ' ' . max($days, 0) . ' 天，到期 ' . date('Y-m-d', $endTime),
        );
    }

    /** 组名（mac_group.group_name 是 urlencode 存的，与后台展示同口径先解码）。 */
    private function groupName(int $groupId): string
    {
        $name = urldecode((string)Db::name('group')->where('group_id', $groupId)->value('group_name'));
        return $name !== '' ? $name : ('组 ' . $groupId);
    }

    public function ensureExt(int $userId, int $inviterId = 0): array
    {
        $table = Installer::prefix() . 'cuican_user_ext';
        $row = Db::table($table)->where('user_id', $userId)->find();
        if ($row) {
            return $row;
        }
        Db::table($table)->insert([
            'user_id' => $userId,
            'gold' => 0,
            'points' => 0,
            'agent_uid' => $inviterId,
            'agent_level' => $inviterId > 0 ? 1 : 0,
            'view_seconds' => 0,
            'updated_at' => time(),
        ]);
        return Db::table($table)->where('user_id', $userId)->find() ?: [];
    }

    public function addGold(int $userId, int $delta): void
    {
        $table = Installer::prefix() . 'cuican_user_ext';
        $this->ensureExt($userId);
        Db::table($table)->where('user_id', $userId)->inc('gold', $delta)->update(['updated_at' => time()]);
    }

    /** 用户积分余额（mac_user.user_points）。 */
    public function points(int $userId): int
    {
        return (int)Db::name('user')->where('user_id', $userId)->value('user_points');
    }

    /**
     * 改积分（正负都收：后台「会员管理」允许扣分）。
     *
     * 积分走 `mac_user.user_points`（macCMS 原生列），不另存一份，后台会员列表才看得见；
     * 同时补一条 `mac_plog`，否则后台积分日志里查不到这笔变动的来源。
     * 日志点数记绝对值，方向由 `plog_type` 决定（收入段 / 支出段，见 [Plog]）。
     */
    public function addPoints(int $userId, int $delta, int $plogType = Plog::TYPE_TASK, string $remarks = ''): void
    {
        if ($userId <= 0 || $delta === 0) {
            return;
        }
        Db::name('user')->where('user_id', $userId)->setInc('user_points', $delta);
        (new Plog())->record($userId, $plogType, abs($delta), $remarks);
    }

    /**
     * 条件扣金币：余额够才扣，返回是否扣成功。
     *
     * 和 addGold 分开的原因：提现申请这类"扣了才能继续"的场景，判断余额与扣减
     * 必须是同一条 UPDATE。先 SELECT 再 INCR 的话，两个并发请求读到同一个旧余额，
     * 各自扣成功，金币被扣成负数（单笔金额大时等于平台倒贴）。
     *
     * 注意用链式的 dec()：TP5.0 的 setDec() 会立刻执行 UPDATE 并清空条件，
     * 再链一个 update(['updated_at'=>..]) 就会丢掉 where。
     */
    public function deductGold(int $userId, int $needGold): bool
    {
        if ($needGold <= 0) {
            return false;
        }
        $affected = Db::table(Installer::prefix() . 'cuican_user_ext')
            ->where('user_id', $userId)
            ->where('gold', '>=', $needGold)
            ->dec('gold', $needGold)
            ->update(['updated_at' => time()]);
        return $affected > 0;
    }

    /**
     * 条件扣积分（mac_user.user_points），余额不足返回 false，语义同 [deductGold]。
     *
     * 扣成功补一条 `plog_type = 8`（积分消费）的日志：点数记绝对值，方向由类型决定，
     * 与核心 `index/controller/User.php` 扣分买内容的写法一致。
     */
    public function deductPoints(int $userId, int $points): bool
    {
        if ($points <= 0) {
            return false;
        }
        $affected = Db::name('user')
            ->where('user_id', $userId)
            ->where('user_points', '>=', $points)
            ->dec('user_points', $points)
            ->update();
        if ($affected <= 0) {
            return false;
        }
        (new Plog())->record($userId, Plog::TYPE_CONSUME, $points, '积分消费');
        return true;
    }
}
