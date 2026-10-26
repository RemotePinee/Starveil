<?php
/**
 * 账号：登录 / 注册 / 验证码 / 找回密码。
 *
 * 与的差别：
 * - 验证码在服务端校验（旧 `reg.html` 提交时根本不带 code，等于没验证码）；
 * - 发码有 60 秒冷却与每日次数上限（无限制，可被刷短信费）；
 * - 密码用 password_hash，旧的 md5 在校验成功后自动升级。
 */

declare(strict_types=1);

namespace Starveil\Api\Service;

use Starveil\Api\Installer;
use Starveil\Api\Repo\Users;
use Starveil\Api\Support\Setting;
use Starveil\Api\Support\Sms;
use think\Db;

final class Account
{
    /** 恢复冷却：同一恢复锚 24 小时内只允许转移一次。 */
    private const RECOVER_COOLDOWN = 86400;

    /** 连续失败几次后锁定，以及锁定与计数窗口的秒数。 */
    private const LOGIN_MAX_FAIL = 5;
    private const LOGIN_LOCK_SECONDS = 900;

    public function __construct(private Users $users = new Users())
    {
    }

    /** @return array{code:int,msg:string,user?:array} */
    public function login(string $userName, string $password): array
    {
        // 失败锁定：macCMS 的登录没有次数限制，6 位纯数字密码单机就能刷开。
        // 计数放在 cache 里（同一账号维度），成功登录清零，锁 15 分钟。
        $failKey = 'starveil_login_fail_' . md5(strtolower($userName));
        if ((int)cache($failKey) >= self::LOGIN_MAX_FAIL) {
            return ['code' => 429, 'msg' => '尝试过于频繁，请 15 分钟后再试'];
        }

        $user = $this->users->findByUserName($userName);
        if (!$user) {
            self::countFailure($failKey);
            // 不区分"用户不存在"与"密码错误"，避免被用来枚举账号
            return ['code' => 403, 'msg' => '账号或密码错误'];
        }
        if ((int)$user['user_status'] !== 1) {
            return ['code' => 403, 'msg' => '账号已被禁用'];
        }
        $rehash = null;
        if (!$this->users->verifyPassword($password, (string)$user['user_pwd'], $rehash)) {
            self::countFailure($failKey);
            return ['code' => 403, 'msg' => '账号或密码错误'];
        }
        if ($rehash !== null) {
            $this->users->updatePasswordHash((int)$user['user_id'], $rehash);
        }
        cache($failKey, null);
        return ['code' => 200, 'msg' => '登录成功', 'user' => $this->users->findById((int)$user['user_id']) ?? []];
    }

    /** 失败次数 +1，超限后由 [login] 的入口判断拦下。 */
    private static function countFailure(string $key): void
    {
        $next = (int)cache($key) + 1;
        cache($key, $next, self::LOGIN_LOCK_SECONDS);
    }

    /**
     * 注册。
     *
     * 校验规则跟随后台 `register_modes`，不再写死"必须是手机号"：
     * - 勾了 `phone` 且用户名是手机号 → 必须带验证码；
     * - 勾了 `account` → 用户名按账号规则（4-20 位字母/数字/下划线），不要求验证码；
     * - 两种都没勾 → 拒绝注册（后台把注册方式全关掉就是"只允许设备/登录，不允许注册"）。
     *
     * 老逻辑把手机号正则写在函数第一行，导致后台勾了「账号 + 密码」也注册不了，
     * 客户端表单还会因为后端报错表现成"网络问题"。
     *
     * @return array{code:int,msg:string,user?:array}
     */
    public function register(
        string $userName,
        string $password,
        string $passwordConfirm,
        string $inviteCode,
        string $smsCode,
    ): array {
        $modes = Setting::json('register_modes', ['account']);
        $phoneMode = in_array('phone', $modes, true);
        $accountMode = in_array('account', $modes, true);
        $isPhone = preg_match('/^1[3-9]\d{9}$/', $userName) === 1;

        if ($isPhone && $phoneMode) {
            // 手机号注册：验证码是强制的，`register_require_sms` 只影响账号注册，
            // 不再读它，避免后台把它关掉就出现"手机号注册不要码"的漏洞
            if (!$this->consumeCode($userName, 'register', $smsCode)) {
                return ['code' => 400, 'msg' => '验证码错误或已过期'];
            }
        } elseif ($accountMode && !$isPhone) {
            if (!preg_match('/^[A-Za-z0-9_]{4,20}$/', $userName)) {
                return ['code' => 400, 'msg' => '账号需 4-20 位字母、数字或下划线'];
            }
            if (Setting::int('register_require_sms', 0) === 1 && !$this->consumeCode($userName, 'register', $smsCode)) {
                return ['code' => 400, 'msg' => '验证码错误或已过期'];
            }
        } else {
            return ['code' => 400, 'msg' => '当前未开放该注册方式'];
        }

        if (strlen($password) < 6 || strlen($password) > 32) {
            return ['code' => 400, 'msg' => '密码长度需 6-32 位'];
        }
        if ($password !== $passwordConfirm) {
            return ['code' => 400, 'msg' => '两次密码不一致'];
        }
        if ($this->users->findByUserName($userName)) {
            return ['code' => 400, 'msg' => $isPhone ? '该手机号已注册' : '该账号已被占用'];
        }

        $inviterId = $inviteCode === '' ? 0 : (int)Db::name('user')->where('user_id', (int)$inviteCode)->value('user_id');
        $userId = $this->users->create($userName, $password, $inviterId);
        return ['code' => 200, 'msg' => '注册成功', 'user' => $this->users->findById($userId) ?? []];
    }

    /**
     * 设备账号登录（RDA，Recoverable Device Account）。
     *
     * 两段式凭据，把设备指纹当密码（`Base64(ANDROID_ID)`）的做法完全不用：
     * - `device_token`：客户端生成的随机 32 字节，服务端只存 sha256，登录凭据；
     * - `recovery`：客户端上报的设备指纹（SSAID），服务端做 HMAC 后只当"找回索引"，
     *   永远不能单独换会话。
     *
     * 三种落法：
     * 1. token 命中 -> 直接登录；
     * 2. token 未命中、recovery 命中 -> 把账号的凭据转移到新 token（即重装/换机后找回），
     *    受 24 小时冷却限制；
     * 3. 都不命中 -> 建号并绑定。
     *
     * @return array{code:int,msg:string,user?:array,token?:string}
     */
    public function deviceLogin(string $token, string $deviceName, string $recovery): array
    {
        $token = strtolower(trim($token));
        if (!preg_match('/^[a-f0-9]{64}$/', $token)) {
            return ['code' => 400, 'msg' => '设备凭据无效，请重新登录'];
        }
        $deviceHash = hash('sha256', $token);
        $deviceName = mb_substr(trim($deviceName), 0, 64);
        $table = Installer::prefix() . 'cuican_user_ext';

        $bound = Db::table($table)->where('device_hash', $deviceHash)->find();
        if ($bound) {
            $user = $this->users->findById((int)$bound['user_id']);
            if ($user === null || (int)($user['user_status'] ?? 0) !== 1) {
                return ['code' => 403, 'msg' => '账号已被禁用'];
            }
            $patch = ['updated_at' => time()];
            if ($deviceName !== '' && (string)($bound['device_name'] ?? '') !== $deviceName) {
                $patch['device_name'] = $deviceName;
            }
            Db::table($table)->where('user_id', (int)$bound['user_id'])->update($patch);
            return ['code' => 200, 'msg' => '登录成功', 'user' => $user, 'token' => $token];
        }

        $anchor = $recovery === '' ? '' : $this->recoveryAnchor($recovery);
        if ($anchor !== '') {
            $recovered = $this->recoverByAnchor($anchor, $deviceHash, $deviceName, $table);
            if ($recovered !== null) {
                $recovered['token'] = $token;
                return $recovered;
            }
        }
        $created = $this->createDeviceAccount($deviceHash, $deviceName, $anchor, $table);
        $created['token'] = $token;
        return $created;
    }

    /**
     * 按恢复锚找回账号，并把设备凭据换到新 token 上。
     *
     * 冷却期内直接拒绝：恢复锚是设备指纹派生的，被撞出来时唯一能挡的就是频率。
     *
     * @return array{code:int,msg:string,user?:array,token?:string}|null
     */
    private function recoverByAnchor(string $anchor, string $deviceHash, string $deviceName, string $table): ?array
    {
        if (Setting::int('device_recovery', 1) !== 1) {
            return null;
        }
        $row = Db::table($table)->where('recovery_hash', $anchor)->find();
        if (!$row) {
            return null;
        }
        $last = (int)($row['recover_at'] ?? 0);
        if ($last > 0 && time() - $last < self::RECOVER_COOLDOWN) {
            return ['code' => 429, 'msg' => '设备账号刚被找回，请 24 小时后再试，或用手机号登录'];
        }
        $userId = (int)$row['user_id'];
        $user = $this->users->findById($userId);
        if ($user === null || (int)($user['user_status'] ?? 0) !== 1) {
            return ['code' => 403, 'msg' => '账号已被禁用'];
        }
        Db::table($table)->where('user_id', $userId)->update([
            'device_hash' => $deviceHash,
            'device_name' => $deviceName,
            'recover_at' => time(),
            'updated_at' => time(),
        ]);
        return ['code' => 200, 'msg' => '已找回设备账号', 'user' => $user];
    }

    /**
     * 首次建号：用户名由凭据派生，密码随机且不告知任何人。
     *
     * 用户名固定成 `d` + 12 位十六进制，后台一眼能看出是设备账号；
     * 密码只是为了满足 macCMS 的 NOT NULL 与后续 password_hash 校验，登录从不使用。
     *
     * @return array{code:int,msg:string,user?:array,token?:string}
     */
    private function createDeviceAccount(string $deviceHash, string $deviceName, string $anchor, string $table): array
    {
        $userName = $this->deviceUserName($deviceHash);
        $userId = $this->users->create($userName, bin2hex(random_bytes(16)), 0);
        $this->users->ensureExt($userId);

        $patch = ['device_hash' => $deviceHash, 'device_name' => $deviceName, 'updated_at' => time()];
        if ($anchor !== '') {
            // 同一台设备换个 token 又来建号时，唯一索引会拦住；此时清掉旧账号的锚，
            // 让"设备指纹 -> 一个账号"的约束仍然成立
            Db::table($table)->where('recovery_hash', $anchor)->update(['recovery_hash' => null]);
            $patch['recovery_hash'] = $anchor;
        }
        Db::table($table)->where('user_id', $userId)->update($patch);

        return [
            'code' => 200,
            'msg' => '登录成功',
            'user' => $this->users->findById($userId) ?? [],
            'token' => '',
        ];
    }

    /** 设备账号用户名：同一凭据永远得到同一个名字，方便排查重号。 */
    private function deviceUserName(string $deviceHash): string
    {
        $base = 'd' . substr($deviceHash, 0, 12);
        if ($this->users->findByUserName($base) === null) {
            return $base;
        }
        // 同一 token 并发建号时才会走到这里，加随机尾巴避免注册失败
        return 'd' . substr($deviceHash, 0, 8) . bin2hex(random_bytes(3));
    }

    /**
     * 恢复锚：设备指纹的 HMAC，盐只存在服务端。
     *
     * 客户端上报的是原始 SSAID，服务端算完只存哈希；即使库被拖走也推不回设备指纹，
     * 更算不出会话。
     */
    private function recoveryAnchor(string $raw): string
    {
        $salt = Setting::get('device_recovery_salt');
        if ($salt === '') {
            $salt = bin2hex(random_bytes(16));
            Setting::set('device_recovery_salt', $salt);
        }
        return hash_hmac('sha256', trim($raw), $salt);
    }

    /** @return array{code:int,msg:string} */
    public function sendCode(string $phone, string $purpose): array
    {
        if (!preg_match('/^1[3-9]\d{9}$/', $phone)) {
            return ['code' => 400, 'msg' => '请输入正确的手机号'];
        }
        if (!in_array($purpose, ['register', 'reset'], true)) {
            return ['code' => 400, 'msg' => '未知的验证码用途'];
        }

        $table = Installer::prefix() . 'cuican_sms_code';
        $recent = Db::table($table)
            ->where('phone', $phone)
            ->where('purpose', $purpose)
            ->where('created_at', '>', time() - 60)
            ->find();
        if ($recent) {
            return ['code' => 429, 'msg' => '请求过于频繁，请 60 秒后再试'];
        }
        $todayCount = Db::table($table)
            ->where('phone', $phone)
            ->where('created_at', '>', strtotime('today'))
            ->count();
        if ($todayCount >= Setting::int('sms_daily_limit', 10)) {
            return ['code' => 429, 'msg' => '今日验证码已达上限'];
        }

        $code = (string)random_int(100000, 999999);
        Db::table($table)->insert([
            'phone' => $phone,
            'code' => $code,
            'purpose' => $purpose,
            'expire_at' => time() + Setting::int('sms_ttl_seconds', 300),
            'used' => 0,
            'created_at' => time(),
        ]);

        $result = Sms::send($phone, $code, $purpose);
        if ($result['code'] !== 1) {
            return ['code' => 500, 'msg' => $result['msg']];
        }
        return ['code' => 200, 'msg' => '验证码已发送'];
    }

    /** @return array{code:int,msg:string} */
    public function resetPassword(
        string $phone,
        string $password,
        string $passwordConfirm,
        string $smsCode,
    ): array {
        if ($password !== $passwordConfirm) {
            return ['code' => 400, 'msg' => '两次密码不一致'];
        }
        if (strlen($password) < 6 || strlen($password) > 32) {
            return ['code' => 400, 'msg' => '密码长度需 6-32 位'];
        }
        $user = $this->users->findByUserName($phone);
        if (!$user) {
            return ['code' => 400, 'msg' => '该手机号未注册'];
        }
        if (!$this->consumeCode($phone, 'reset', $smsCode)) {
            return ['code' => 400, 'msg' => '验证码错误或已过期'];
        }
        $this->users->updatePasswordHash((int)$user['user_id'], password_hash($password, PASSWORD_DEFAULT));
        return ['code' => 200, 'msg' => '密码已重置'];
    }

    /** 校验并消耗验证码：成功即置 used=1，防重放。 */
    private function consumeCode(string $phone, string $purpose, string $code): bool
    {
        if (!preg_match('/^\d{6}$/', $code)) {
            return false;
        }
        $table = Installer::prefix() . 'cuican_sms_code';
        $row = Db::table($table)
            ->where('phone', $phone)
            ->where('purpose', $purpose)
            ->where('used', 0)
            ->where('expire_at', '>=', time())
            ->order('created_at desc')
            ->find();
        if (!$row || !hash_equals((string)$row['code'], $code)) {
            return false;
        }
        Db::table($table)
            ->where('phone', $phone)
            ->where('purpose', $purpose)
            ->where('created_at', (int)$row['created_at'])
            ->update(['used' => 1]);
        return true;
    }
}
