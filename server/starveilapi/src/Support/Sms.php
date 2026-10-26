<?php
/**
 * 短信发送。
 *
 * 通道做成可配置的 HTTP 模板，不内置任何厂商密钥（历史插件把讯飞 appid 写进 SQL 种子）。
 * 未配置时明确报"短信通道未配置"，而不是静默返回成功——后者会让用户永远收不到码。
 */

declare(strict_types=1);

namespace Starveil\Api\Support;

final class Sms
{
    /**
     * @return array{code:int,msg:string}
     */
    public static function send(string $phone, string $code, string $purpose): array
    {
        $urlTemplate = Setting::get('sms_http_url');
        if ($urlTemplate === '') {
            return ['code' => 0, 'msg' => '短信通道未配置，请联系管理员'];
        }

        $url = str_replace(
            ['{phone}', '{code}', '{purpose}'],
            [rawurlencode($phone), rawurlencode($code), rawurlencode($purpose)],
            $urlTemplate,
        );

        $ch = curl_init($url);
        if ($ch === false) {
            return ['code' => 0, 'msg' => '短信通道初始化失败'];
        }
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT => 5,
            CURLOPT_CONNECTTIMEOUT => 3,
            // 短信网关是管理员配置的固定地址，禁止跟随重定向以免被劫持
            CURLOPT_FOLLOWLOCATION => false,
        ]);
        $body = curl_exec($ch);
        $status = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
        curl_close($ch);

        if ($body === false || $status >= 400) {
            return ['code' => 0, 'msg' => '短信发送失败，请稍后重试'];
        }
        return ['code' => 1, 'msg' => 'ok'];
    }
}
