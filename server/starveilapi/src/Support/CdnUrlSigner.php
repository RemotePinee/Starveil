<?php
/**
 * 播放地址签名：腾讯云「Key 防盗链」（`tencent`）与自建媒体服务（`self`）两种模式。
 *
 * 模式由后台 `cdn_sign_type` 决定；两种模式共用同一套参数与同一个 `cdn_sign_key`，
 * 区别只有两处：URL 里带不带源站 host（`src`）、`src` 是否并入签名串。
 *
 * 为什么签名必须在服务端：地址一旦不带签名就能被任意重放，客户端做的"只能看前 N 分钟"
 * 只是本机的一个 if——换播放器、抓包重放都能绕开，所以截断只能由中间层（CDN 或媒体
 * 服务）执行，这里只负责"发一个能被中间层验证的地址"。
 *
 *   tencent  sign = md5(KEY + Dir + t + exper + rlimit + us)
 *   self     sign = md5(KEY + Dir + t + exper + rlimit + us + src)
 *
 * 为什么需要 self：macCMS 的 `mac_vod.vod_play_url` 存的是**完整第三方直链**
 * （`https://采集站A.com/vod/1.m3u8`）。腾讯云那套只取 `cdn_domain + path`，源站域名在
 * 拼串前就被丢掉了；自建媒体服务收到 `/vod/1.m3u8` 不知道回 A 还是 B 取，不同源站的
 * 同 path 还会互相顶掉。所以 self 把源站 host 明文放进 `src` 供回源。
 *
 * `src` **必须参与签名**：只当"提示参数"的话，谁都能把它改成免费站，让媒体服务替别人
 * 代理任意源（SSRF），防盗链也等于没开。
 *
 * 参数含义（两种模式一致）：
 *
 * - KEY：后台「CDN 签名密钥」（`cdn_sign_key`，SENSITIVE，只在服务端参与计算）；
 * - Dir：原始地址的 path 去掉文件名那一段（`/vod/2026/a.m3u8` → `/vod/2026/`）。
 *   **不是**整个 path：腾讯的规则里签名只绑目录，同目录下的文件共用一份签名，
 *   所以签名本身拦不住"同目录另一集"，能拦的是目录之外与过期时间；
 * - t：过期时间戳（Unix 秒）的**小写十六进制**（`dechex`），有效期见 [TOKEN_TTL]；
 * - exper：试看秒数，0 = 不截断；这是**CDN / 媒体服务侧**的截断时长，不是客户端上限；
 * - rlimit：0 = 不限次数（同样参与签名，改值必须同步改拼串）；
 * - us：随机串，只为让每一次下发的地址不重复；
 * - src：原始直链的 host（`parse_url` 的 `host`，转小写；带非默认端口时拼回 `:端口`）。
 *   `parse_url` 把 host 与 port 分开放，端口必须自己拼回去——只写 host 会把
 *   `https://a.com:8443/x` 和 `https://a.com/x` 混成同一个源站。
 *
 * 下发 URL 的参数顺序同样固定：`src` 在最前（它是资源定位信息，不是签名参数），
 * 之后固定 `t, exper, rlimit, us, sign`——腾讯的实现按这个顺序拼串校验，顺序变了就播不了。
 * 原始地址自带的 query 原样保留在签名参数之前。
 *
 * 为什么 `us` 每集单独随机、不与整部剧共用：安全边界由 Dir 决定（同目录同签名可互用），
 * 共用 `us` 只会让一部剧的每集地址长得一模一样，排查与 CDN 缓存都没法区分；每集随机的
 * 代价只是一次 `random_bytes(8)`，所以按集随机。
 *
 * 域名替换：`cdn_domain + 原始 path + query`。tencent 模式下源站片源必须已上传到 CDN，
 * 且**路径与源站完全一致**，否则签名正确也 404/403；self 模式下 `cdn_domain` 是媒体服务
 * 自己的域名，由它按 `src` 回源（见 README 的 CDN 配置说明）。
 */

declare(strict_types=1);

namespace Starveil\Api\Support;

final class CdnUrlSigner
{
    /** 腾讯云点播 / CDN：URL 不带源站信息，腾讯云不认 `src` 参数。 */
    public const TYPE_TENCENT = 'tencent';

    /** 自建媒体服务：URL 带 `src`（源站 host），媒体服务按它回源。 */
    public const TYPE_SELF = 'self';

    /** 合法取值；后台下拉与 [fromSettings] 共用同一份。 */
    public const TYPES = [self::TYPE_TENCENT, self::TYPE_SELF];

    /** 签名有效期：6 小时。够一部短剧连续看，又短到泄露的地址隔天就失效。 */
    public const TOKEN_TTL = 6 * 3600;

    /** 播放次数限制：0 = 不限制。参与签名，改值必须同步改拼串。 */
    private const RLIMIT = 0;

    /** 随机串字节数：8 字节 → 16 位十六进制。 */
    private const US_BYTES = 8;

    /** 各 scheme 的默认端口：写出来也不进 `src`，`a.com:443` 与 `a.com` 视为同一源站。 */
    private const DEFAULT_PORTS = ['http' => 80, 'https' => 443];

    public function __construct(
        private readonly string $domain,
        private readonly string $key,
        private readonly string $type = self::TYPE_TENCENT,
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
        $type = self::normalizeType(Setting::get('cdn_sign_type', self::TYPE_TENCENT));

        return new self($domain, $key, $type);
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
     * 原始地址 → 签名地址；地址没法改写时返回 null（调用方据此"不给地址"）。
     *
     * 只认带绝对 path 的地址（`/upload/vod/x.mp4` 或完整直链 `https://a.com/vod/1.m3u8`）：
     * 那是 `mac_vod.vod_play_url` 里存的形态。空串、`a.m3u8` 这种没有前导斜杠的值一律
     * 返回 null——与其猜一个打不开的地址，不如让调用方保持"锁着"。
     *
     * self 模式额外要求原始地址带 host：相对路径没有源站可回，签了媒体服务也无从取，同样
     * 返回 null（调用方的回退规则与"签不出来"一致）。
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

        // 空串 = 不带 src（tencent 模式）；self 模式取不到 host 就没法回源，直接不给地址
        $src = $this->type === self::TYPE_SELF ? self::sourceHost($parts) : '';
        if ($this->type === self::TYPE_SELF && $src === '') {
            return null;
        }

        // path 一定以 `/` 开头，strrpos 至少命中 0，取不到时兜底成根目录
        $slash = strrpos($path, '/');
        $dir = $slash === false ? '/' : substr($path, 0, $slash + 1);

        $t = dechex(($now ?? time()) + self::TOKEN_TTL);
        $exper = max(0, $exper);
        $us = bin2hex(random_bytes(self::US_BYTES));
        $sign = md5($this->key . $dir . $t . $exper . self::RLIMIT . $us . $src);

        $query = (string)($parts['query'] ?? '');
        $url = $this->domain . $path . ($query === '' ? '' : '?' . $query);

        return $url . ($query === '' ? '?' : '&')
            . ($src === '' ? '' : 'src=' . rawurlencode($src) . '&')
            . 't=' . $t
            . '&exper=' . $exper
            . '&rlimit=' . self::RLIMIT
            . '&us=' . $us
            . '&sign=' . $sign;
    }

    /**
     * 源站 host：`parse_url` 的 `host` 转小写，带非默认端口时拼回 `:端口`。
     *
     * 端口单独占 `parse_url` 的一个键，不拼回去的话 `a.com:8443` 会退化成 `a.com`，
     * 指向另一个源站。默认端口（http:80 / https:443）不写——`a.com:443` 与 `a.com`
     * 是同一个源站，写成两种会让媒体服务的缓存与白名单各算一份。
     *
     * @param array<string,mixed> $parts
     */
    private static function sourceHost(array $parts): string
    {
        $host = strtolower(trim((string)($parts['host'] ?? '')));
        if ($host === '') {
            return '';
        }
        $port = (int)($parts['port'] ?? 0);
        $scheme = strtolower((string)($parts['scheme'] ?? ''));
        if ($port <= 0 || $port === (self::DEFAULT_PORTS[$scheme] ?? 0)) {
            return $host;
        }

        return $host . ':' . $port;
    }

    /** 非法值按 tencent：老库升级上来没有这个键，行为必须与升级前完全一致。 */
    private static function normalizeType(string $type): string
    {
        $type = strtolower(trim($type));

        return in_array($type, self::TYPES, true) ? $type : self::TYPE_TENCENT;
    }
}
