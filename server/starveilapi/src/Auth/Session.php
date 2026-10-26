<?php
/**
 * Cookie 会话。
 *
 * **必须与 macCMS 完全一致**，否则网页与 App 会变成两套登录态：
 * CMS 的 `model('User')->checkLogin()`（application/common/model/User.php）校验
 * `user_check = md5(user_random-user_name-user_id-)`，而弹幕、评论这些 CMS 内置接口都走它。
 * 插件早先用的是自定义 HMAC 串，结果 App 明明登录了，调 `/api.php/danmaku/send`
 * 仍然回「请先登录」。
 *
 * 安全性没有降低：`user_random` 只存在库里（macCMS 自己也是这么校验的），
 * 不知道它就算不出 user_check；cookie 有效期仍是 30 天。
 */

declare(strict_types=1);

namespace Starveil\Api\Auth;

use Starveil\Api\Repo\Users;
use think\Db;

final class Session
{
    public const COOKIE_USER_ID = 'user_id';
    public const COOKIE_USER_NAME = 'user_name';
    public const COOKIE_NICK_NAME = 'user_nick_name';
    public const COOKIE_GROUP_ID = 'group_id';
    public const COOKIE_GROUP_NAME = 'group_name';
    public const COOKIE_CHECK = 'user_check';
    public const COOKIE_PORTRAIT = 'user_portrait';

    private const TTL = 2592000; // 30 天

    public function __construct(private ?array $user)
    {
    }

    /** 当前登录用户；未登录返回 null（不抛异常，由控制器决定是否要求登录）。 */
    public static function current(): self
    {
        $uid = (int)trim((string)($_COOKIE[self::COOKIE_USER_ID] ?? ''));
        $name = urldecode(trim((string)($_COOKIE[self::COOKIE_USER_NAME] ?? '')));
        $check = urldecode(trim((string)($_COOKIE[self::COOKIE_CHECK] ?? '')));
        if ($uid <= 0 || $name === '' || $check === '') {
            return new self(null);
        }

        $user = (new Users())->findById($uid);
        if ($user === null || (int)($user['user_status'] ?? 0) !== 1) {
            return new self(null);
        }
        if (!hash_equals(self::checkOf($user), $check)) {
            return new self(null);
        }

        return new self($user);
    }

    public function user(): ?array
    {
        return $this->user;
    }

    public function id(): int
    {
        return (int)($this->user['user_id'] ?? 0);
    }

    public function loggedIn(): bool
    {
        return $this->id() > 0;
    }

    /** 登录/注册成功后下发 cookie。 */
    public function issue(array $user): void
    {
        $uid = (int)$user['user_id'];
        $user = $this->ensureRandom($uid, $user);
        $exp = time() + self::TTL;
        $secure = !empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off';

        $cookies = [
            self::COOKIE_USER_ID => (string)$uid,
            self::COOKIE_USER_NAME => (string)($user['user_name'] ?? ''),
            self::COOKIE_NICK_NAME => (string)($user['user_nick_name'] ?? ''),
            self::COOKIE_GROUP_ID => (string)($user['group_id'] ?? 1),
            self::COOKIE_GROUP_NAME => (string)($user['group_name'] ?? ''),
            self::COOKIE_CHECK => self::checkOf($user),
            self::COOKIE_PORTRAIT => (string)($user['user_portrait'] ?? ''),
        ];

        foreach ($cookies as $name => $value) {
            setcookie($name, rawurlencode($value), [
                'expires' => $exp,
                'path' => '/',
                'secure' => $secure,
                'httponly' => true,
                'samesite' => 'Lax',
            ]);
            $_COOKIE[$name] = $value;
        }
    }

    public function clear(): void
    {
        foreach ([
            self::COOKIE_USER_ID,
            self::COOKIE_USER_NAME,
            self::COOKIE_NICK_NAME,
            self::COOKIE_GROUP_ID,
            self::COOKIE_GROUP_NAME,
            self::COOKIE_CHECK,
            self::COOKIE_PORTRAIT,
        ] as $name) {
            setcookie($name, '', ['expires' => time() - 3600, 'path' => '/', 'samesite' => 'Lax']);
            unset($_COOKIE[$name]);
        }
    }

    /**
     * 撤销当前会话：轮换 `user_random` 后清 cookie。
     *
     * `user_check` 由 `user_random` 派生，macCMS 改密码时并不轮换它，旧 cookie
     * 在 30 天有效期内一直可用。退出时轮换一次，App 与网页的旧凭据同时失效。
     */
    public function revoke(): void
    {
        if ($this->user !== null) {
            try {
                Db::name('user')
                    ->where('user_id', (int)($this->user['user_id'] ?? 0))
                    ->update(['user_random' => bin2hex(random_bytes(16))]);
            } catch (\Throwable $e) {
                // 轮换失败不阻断退出：最差只是旧 cookie 还能用，不能让人退不出去
                error_log('[starveil] 轮换 user_random 失败 uid=' . (int)($this->user['user_id'] ?? 0));
            }
        }
        $this->clear();
    }

    /** macCMS 的登录校验串：`md5(user_random-user_name-user_id-)`。 */
    private static function checkOf(array $user): string
    {
        return md5(
            (string)($user['user_random'] ?? '') . '-'
            . (string)($user['user_name'] ?? '') . '-'
            . (int)($user['user_id'] ?? 0) . '-',
        );
    }

    /** 老用户可能没有 user_random（macCMS 允许为空），登录时补一个并落库。 */
    private function ensureRandom(int $uid, array $user): array
    {
        if ((string)($user['user_random'] ?? '') !== '') {
            return $user;
        }
        $random = bin2hex(random_bytes(16));
        try {
            Db::name('user')->where('user_id', $uid)->update(['user_random' => $random]);
        } catch (\Throwable $e) {
            return $user;
        }
        $user['user_random'] = $random;
        return $user;
    }
}
