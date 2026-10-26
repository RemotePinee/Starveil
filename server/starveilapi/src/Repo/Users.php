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

    /** 第三方登录字段白名单：不接受客户端拼列名。 */
    private const OPENID_FIELDS = [
        'weixin' => 'user_openid_weixin',
        'qq' => 'user_openid_qq',
    ];

    public function findById(int $userId): ?array
    {
        if ($userId <= 0) {
            return null;
        }
        $row = Db::name('user')->where('user_id', $userId)->find();
        if (!$row) {
            return null;
        }
        // group_id 可能是 "1,3" 这种多组串，组名取第一个有效组
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

    public function findByOpenid(string $provider, string $openid): ?array
    {
        $column = self::OPENID_FIELDS[$provider] ?? null;
        if ($column === null || $openid === '') {
            return null;
        }
        $row = Db::name('user')->where($column, $openid)->find();
        return $row ?: null;
    }

    /** 注册。返回新用户 id；用户名重复由调用方先查。 */
    public function create(string $userName, string $plainPassword, int $inviterId): int
    {
        $now = time();
        $userId = (int)Db::name('user')->insertGetId([
            'user_name' => $userName,
            'user_pwd' => $this->hashPassword($plainPassword),
            'user_nick_name' => $userName,
            'user_phone' => $userName,
            // macCMS 的 group_id 是 varchar（支持逗号分隔多组），默认组必须写成字符串
            'group_id' => '1',
            'user_points' => 0,
            'user_status' => 1,
            'user_reg_time' => $now,
            'user_reg_ip' => (int)sprintf('%u', ip2long((string)($_SERVER['REMOTE_ADDR'] ?? '0.0.0.0'))),
            'user_pid' => $inviterId,
        ]);
        $this->ensureExt($userId, $inviterId);
        return $userId;
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

    public function updatePasswordHash(int $userId, string $hash): void
    {
        Db::name('user')->where('user_id', $userId)->update(['user_pwd' => $hash]);
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
        $groups = array_filter(array_map('trim', explode(',', (string)$user['group_id'])));
        if (!in_array((string)$groupId, $groups, true)) {
            $groups[] = (string)$groupId;
        }
        $base = max(time(), (int)($user['user_end_time'] ?? 0));
        Db::name('user')->where('user_id', $userId)->update([
            'group_id' => implode(',', $groups),
            'user_end_time' => $base + max($days, 0) * 86400,
        ]);
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

    /** 积分走 mac_user.user_points（macCMS 原生列），不另存一份，后台会员列表才看得见。 */
    public function addPoints(int $userId, int $delta): void
    {
        Db::name('user')->where('user_id', $userId)->setInc('user_points', $delta);
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

    /** 条件扣积分（mac_user.user_points），余额不足返回 false，语义同 [deductGold]。 */
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
        return $affected > 0;
    }
}
