<?php
/** 账号：登录 / 注册 / 验证码 / 找回密码。 */

declare(strict_types=1);

namespace Starveil\Api\Controller;

use Starveil\Api\Auth\Session;
use Starveil\Api\Http\Json;
use Starveil\Api\Service\Account;

final class AuthController extends BaseController
{
    public function postLogin(): void
    {
        $result = (new Account())->login($this->input->str('user_name'), $this->input->str('user_pwd'));
        if ($result['code'] !== 200) {
            Json::fail((int)$result['code'], $result['msg']);
            return;
        }
        $user = $result['user'] ?? [];
        (new Session(null))->issue($user);
        // data 只回提示串：客户端 `ApiEnvelopeDto<String>` 按字符串解析，
        // 若塞整个 profile 数组，反序列化会失败 -> 表现成"登录不了"。
        // 真正的资料由 `v1.user/detail` 取。
        Json::ok($result['msg'], $result['msg']);
    }
    public function postRegister(): void
    {
        $result = (new Account())->register(
            $this->input->str('user_name'),
            $this->input->str('user_pwd'),
            $this->input->str('user_pwd2'),
            $this->input->str('uid'),
            $this->input->str('code'),
        );
        if ($result['code'] !== 200) {
            Json::fail((int)$result['code'], $result['msg']);
            return;
        }
        $user = $result['user'] ?? [];
        (new Session(null))->issue($user);
        // 同上：注册的 data 也只回提示串
        Json::ok($result['msg'], $result['msg']);
    }

    /**
     * 设备账号登录 `v1.auth/deviceLogin`。
     *
     * 不区分"登录"与"注册"：客户端第一次带随机 token 来就建号，之后同一 token 就是登录。
     * 第三个参数 `recovery` 是设备指纹（SSAID）原文，服务端做 HMAC 后只当找回索引，
     * 详情见 `Service\Account::deviceLogin`。
     */
    public function postDevicelogin(): void
    {
        $result = (new Account())->deviceLogin(
            $this->input->str('device_token'),
            $this->input->str('device_name'),
            $this->input->str('recovery'),
        );
        if ($result['code'] !== 200) {
            Json::fail((int)$result['code'], $result['msg']);
            return;
        }
        (new Session(null))->issue($result['user'] ?? []);
        // token 回给客户端落盘：恢复场景下服务端没有客户端那份明文，只能以返回值为准
        Json::ok(['token' => (string)($result['token'] ?? '')], $result['msg']);
    }

    public function getRegisterSms(): void
    {
        $this->sendCode('register');
    }

    public function getFindpassSms(): void
    {
        $this->sendCode('reset');
    }

    public function getFindpass(): void
    {
        $result = (new Account())->resetPassword(
            $this->input->str('to'),
            $this->input->str('user_pwd'),
            $this->input->str('user_pwd2'),
            $this->input->str('code'),
        );
        $result['code'] === 200
            ? Json::ok(null, $result['msg'])
            : Json::fail((int)$result['code'], $result['msg']);
    }

    private function sendCode(string $purpose): void
    {
        $phone = $this->input->str('to', $this->input->str('phone'));
        $result = (new Account())->sendCode($phone, $purpose);
        $result['code'] === 200
            ? Json::ok(null, $result['msg'])
            : Json::fail((int)$result['code'], $result['msg']);
    }

    /** 只回客户端会用的字段，不回密码哈希、注册 IP 等内部列。 */
    private function profile(array $user): array
    {
        return [
            'user_id' => (int)($user['user_id'] ?? 0),
            'user_name' => (string)($user['user_name'] ?? ''),
            'user_nick_name' => (string)($user['user_nick_name'] ?? ''),
            'user_portrait' => (string)($user['user_portrait'] ?? ''),
            'group_id' => (int)($user['group_id'] ?? 1),
            'group_name' => (string)($user['group_name'] ?? ''),
            'user_end_time' => (int)($user['user_end_time'] ?? 0),
        ];
    }
}
