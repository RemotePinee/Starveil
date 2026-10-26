<?php
/**
 * 读取 macCMS 自带的在线支付配置（系统 → 在线支付配置 / `system/configpay`）。
 *
 * macCMS 把支付参数写在 `application/extra/maccms.php` 的 `pay` 段里，
 * Epay 的键是 `api_url` / `appid` / `appkey`（见核心 `application/common/extend/pay/Epay.php`）。
 * 插件优先复用这份配置，避免运营在两处填同一套商户号；
 * 插件自己的 `cuican_pay_channel` 只作为覆盖/多渠道扩展。
 */

declare(strict_types=1);

namespace Starveil\Api\Support;

use think\Config;

final class MacPay
{
    /** macCMS 的 Epay 配置 → 插件内部的通道结构；没配齐返回 null。 */
    public static function epayChannel(): ?array
    {
        $maccms = Config::get('maccms');
        $epay = is_array($maccms) ? ($maccms['pay']['epay'] ?? null) : null;
        if (!is_array($epay)) {
            return null;
        }
        $url = rtrim(trim((string)($epay['api_url'] ?? '')), '/');
        $pid = trim((string)($epay['appid'] ?? ''));
        $key = trim((string)($epay['appkey'] ?? ''));
        if ($url === '' || $pid === '' || $key === '') {
            return null;
        }
        return [
            'code' => 'epay',
            'name' => '易支付（macCMS 配置）',
            'status' => 1,
            'sort' => 1000,
            'config' => json_encode([
                'driver' => 'epay',
                'url' => $url . '/',
                'pid' => $pid,
                'key' => $key,
                'type' => 'alipay',
            ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
        ];
    }

    public static function isConfigured(): bool
    {
        return self::epayChannel() !== null;
    }

    /** macCMS 后台「在线支付配置」页的入口路径，仅用于后台提示。 */
    public static function configPath(): string
    {
        return '系统 → 在线支付配置';
    }
}
