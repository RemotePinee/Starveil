<?php
/**
 * 腾讯云 CDN「Key 防盗链」签名（TypeD）。
 *
 * 试看只在 CDN 这条路上存在：CDN 关（或域名 / 密钥没配）时 [fromSettings] 恒返回 null，
 * 调用方必须回退到「不给地址」，绝不能退化成原始地址——那等于把付费墙拆了。
 *
 * 签名串，顺序固定，错一个字符 CDN 就 403：
 *
 *   sign = md5(KEY + Dir + t + exper + rlimit + us)
 *
 * - KEY：后台「CDN 签名密钥」（`cdn_sign_key`，SENSITIVE，只在服务端参与计算）；
 * - Dir：原始地址的 path 去掉文件名那一段（`/vod/2026/a.m3u8` → `/vod/2026/`）。
 *   **不是**整个 path：腾讯的规则里签名只绑目录，同目录下的文件共用一份签名，
 *   所以签名本身拦不住"同目录另一集"，能拦的是目录之外与过期时间；
 * - t：过期时间戳（Unix 秒）的**小写十六进制**（`dechex`），有效期见 [TOKEN_TTL]；
 * - exper：试看秒数，0 = 不截断；这是**CDN 侧**的截断时长，不是客户端的播放上限；
 * - rlimit：0 = 不限次数（同样参与签名，改值必须同步改拼串）;
 * - us：随机串，只为让每一次下发的地址不重复。
 *
 * 下发 URL 的参数顺序同样是固定的 `t, exper, rlimit, us, sign`——腾讯的实现按这个顺序
 * 拼串校验，顺序变了就播不了。原始地址自带的 query 原样保留在签名参数之前。
 *
 * 为什么 `us` 每集单独随机、不与整部剧共用：安全边界由 Dir 决定（同目录同签名可互用），
 * 共用 `us` 只会让一部剧的每集地址长得一模一样，排查与 CDN 缓存都没法区分；每集随机的
 * 代价只是一次 `random_bytes(8)`，所以按集随机。
 *
 * 域名替换：`cdn_domain + 原始 path + query`。源站片源必须已上传到 CDN，且**路径与源站
 * 完全一致**，否则签名正确也 404/403（见 README 的 CDN 配置说明）。
 */

declare(strict_types=1);

namespace Starveil\Api\Support;

final class CdnUrlSigner
{
    /** 签名有效期：6 小时。够一部短剧连续看，又短到泄露的地址隔天就失效。 */
    public const TOKEN_TTL = 6 * 3600;

    /** 播放次数限制：0 = 不限制。参与签名，改值必须同步改拼串。 */
    private const RLIMIT = 0;

    /** 随机串字节数：8 字节 → 16 位十六进制。 */
    private const US_BYTES = 8;

    public function __construct(
        private readonly string $domain,
        private readonly string $key,
    ) {
    }

    /**
     * 读后台配置组装签名器。没开 CDN、域名或密钥为空时返回 null。
     *
     * 域名没写协议时按 https 补：后台最常见就填 `v.example.com`，少了协议拼出来的地址
     * 播放器直接不认，而运营很难从"播不了"反推到这里。
     */
    public static function fromSettings(): ?self
    {
        if (Setting::int('cdn_enabled', 0) !== 1) {
            return null;
        }
        $domain = rtrim(trim(Setting::get('cdn_domain', '')), '/');
        $key = trim(Setting::get('cdn_sign_key', ''));
        if ($domain === '' || $key === '') {
            return null;
        }
        if (preg_match('#^https?://#i', $domain) !== 1) {
            $domain = 'https://' . $domain;
        }

        return new self($domain, $key);
    }

    /**
     * 试看秒数 = `cdn_trysee_minutes × 60`；0 = 不给试看（等价于不给地址）。
     *
     * 只有拿到实例（CDN 真接通）时才有意义：CDN 关着的时候这个分钟数不参与任何判定。
     */
    public function previewSeconds(): int
    {
        return max(0, Setting::int('cdn_trysee_minutes', 0)) * 60;
    }

    /**
     * 原始地址 → CDN 签名地址；地址没法改写时返回 null（调用方据此"不给地址"）。
     *
     * 只认站内绝对路径（`/upload/vod/x.mp4`）：那是 `mac_vod.vod_play_url` 里存的形态，
     * 换成 CDN 域名后 path 必须与源站一致。空串、`a.m3u8` 这种没有前导斜杠的值一律
     * 返回 null——与其猜一个打不开的地址，不如让调用方保持"锁着"。
     *
     * `$now` 只给测试固定时间用，线上调用不传。
     */
    public function signedUrl(string $rawUrl, int $exper = 0, ?int $now = null): ?string
    {
        $rawUrl = trim($rawUrl);
        if ($rawUrl === '') {
            return null;
        }
        $parts = parse_url($rawUrl);
        if (!is_array($parts)) {
            return null;
        }
        $path = (string)($parts['path'] ?? '');
        if ($path === '' || $path[0] !== '/') {
            return null;
        }

        // path 一定以 `/` 开头，strrpos 至少命中 0，取不到时兜底成根目录
        $slash = strrpos($path, '/');
        $dir = $slash === false ? '/' : substr($path, 0, $slash + 1);

        $t = dechex(($now ?? time()) + self::TOKEN_TTL);
        $exper = max(0, $exper);
        $us = bin2hex(random_bytes(self::US_BYTES));
        $sign = md5($this->key . $dir . $t . $exper . self::RLIMIT . $us);

        $query = (string)($parts['query'] ?? '');
        $url = $this->domain . $path . ($query === '' ? '' : '?' . $query);

        return $url . ($query === '' ? '?' : '&')
            . 't=' . $t
            . '&exper=' . $exper
            . '&rlimit=' . self::RLIMIT
            . '&us=' . $us
            . '&sign=' . $sign;
    }
}
