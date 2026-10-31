<?php
/**
 * 播放地址签名：腾讯云「Key 防盗链」（`tencent`）与自建媒体服务（`self`）两种模式。
 *
 * 模式由后台 `cdn_sign_type` 决定；两种模式共用同一个 `cdn_sign_key`，区别有三处：
 * URL 里带不带源站 host（`src`）、`src` 是否并入签名串、以及**绑不绑身份**
 * （`self` 把「谁看哪一集」并进签名，`tencent` 绑不了，原因见下）。
 *
 * 为什么签名必须在服务端：地址一旦不带签名就能被任意重放，客户端做的"只能看前 N 分钟"
 * 只是本机的一个 if——换播放器、抓包重放都能绕开，所以截断只能由中间层（CDN 或媒体
 * 服务）执行，这里只负责"发一个能被中间层验证的地址"。
 *
 *   tencent  sign = md5(KEY + Dir + t + exper + rlimit + us)
 *   self     sign = md5(KEY + Dir + t + exper + rlimit + us + src
 *                       + "|" + uid + "|" + vod + "|" + nid + "|" + rev)
 *
 *
 * 升级提示：`self` 的签名串变过（加了身份段与 `rev`），**插件与媒体服务必须同时升级**——
 * 媒体服务还按老串复算的话，所有 `self` 地址一律签名不匹配，表现为全站 403。
 * `tencent` 的签名串一个字都没动，升级不受影响。
 * 为什么需要 self：macCMS 的 `mac_vod.vod_play_url` 存的是**完整第三方直链**
 * （`https://采集站A.com/vod/1.m3u8`）。腾讯云那套只取 `cdn_domain + path`，源站域名在
 * 拼串前就被丢掉了；自建媒体服务收到 `/vod/1.m3u8` 不知道回 A 还是 B 取，不同源站的
 * 同 path 还会互相顶掉。所以 self 把源站 host 明文放进 `src` 供回源。
 *
 * `src` **必须参与签名**：只当"提示参数"的话，谁都能把它改成免费站，让媒体服务替别人
 * 代理任意源（SSRF），防盗链也等于没开。
 *
 * 为什么 `self` 还要绑身份：只绑目录时，同一条地址换任何用户、换同一目录下的任何一集都能
 * 用——付费用户把播放地址贴到群里，所有人在 token TTL 内都能重放，也查不出是谁漏的。
 * 所以 `self` 把 `uid` / `vod` / `nid` 并进签名，并且**放进 URL**：
 *
 * - 媒体服务的契约是「校验签名 → 按 src 回源」，它没有、也不该有用户会话，这些值只能由
 *   URL 送过去；它不需要理解它们的含义，复算签名时算进去即可；
 * - 改任何一个值签名都对不上：换用户（改 `uid`）、换一集（改 `nid`）、换一部剧（改 `vod`）
 *   立刻失效，`Dir` 相同也不再能互相顶用；
 * - 顺带给了媒体服务一条可记账的身份：日志里的 `uid + vod + nid` 就是"谁看了哪一集"，
 *   泄露的地址能定位到具体账号。
 *
 * 诚实的边界：媒体服务**无法**知道"现在拿这条地址来取流的是不是 uid 那个人"——它只验
 * 签名。所以一条合法地址原封不动转给别人，仍然能播到过期（6 小时）。按人拦截需要媒体
 * 服务回调本方校验会话，那是另一套架构；这里能做到的是"改不了、查得到、能一刀切失效"。
 *
 * 「一刀切失效」= `cdn_sign_rev`（整数）：加一，此前下发的 `self` 地址全部作废——签名串里的
 * `rev` 变了，媒体服务复算必然不匹配。泄露后干等 6 小时不可接受，而换 `cdn_sign_key` 会把
 * `tencent` 模式一起打断（两种模式共用一把密钥），所以留这个独立开关。缺省 0 = 不改变任何
 * 语义，行为与没加这一项时一致。
 *
 * 腾讯云模式为什么绑不了身份：腾讯云按它自己写死的签名串校验（`KEY + Dir + t + exper +
 * rlimit + us`），多一个参数、改一处拼接都会签名不匹配、整站 403。这是**厂商契约限制**，
 * 不是这里省略：`tencent` 模式的地址在 TTL 内仍然可被任何人重放，只能靠缩短有效期或轮换
 * 密钥收敛；要真正绑身份就得用 `self` 模式。
 *
 * 参数含义（两种模式一致）：
 *
 * - KEY：后台「CDN 签名密钥」（`cdn_sign_key`，SENSITIVE，只在服务端参与计算）；
 * - Dir：原始地址的 path 去掉文件名那一段（`/vod/2026/a.m3u8` → `/vod/2026/`）。
 *   **不是**整个 path：腾讯的规则里签名只绑目录，同目录下的文件共用一份签名，
 *   所以签名本身拦不住"同目录另一集"，能拦的是目录之外与过期时间；`self` 模式靠
 *   `nid` 参与签名补上这一格（见上）；
 * - t：过期时间戳（Unix 秒）的**小写十六进制**（`dechex`），有效期见 [TOKEN_TTL]；
 * - exper：试看秒数，0 = 不截断；这是**CDN / 媒体服务侧**的截断时长，不是客户端上限；
 * - rlimit：0 = 不限次数（同样参与签名，改值必须同步改拼串）；
 * - us：随机串，只为让每一次下发的地址不重复；
 * - src：原始直链的 host（`parse_url` 的 `host`，转小写；带非默认端口时拼回 `:端口`）。
 *   `parse_url` 把 host 与 port 分开放，端口必须自己拼回去——只写 host 会把
 *   `https://a.com:8443/x` 和 `https://a.com/x` 混成同一个源站；
 * - uid / vod / nid / rev：**只有 `self` 模式有**。`uid` = 登录用户 id（未登录为 0）、
 *   `vod` = 影片 id、`nid` = 集号、`rev` = 失效序号，四段用 `|` 分隔拼在签名串末尾。
 *   分隔符不能省：裸拼时 `uid=1,vod=9,nid=23` 与 `uid=19,vod=2,nid=3` 会拼成同一个串，
 *   绑定等于没做。
 *
 * 下发 URL 的参数顺序同样固定：`tencent` 是 `t, exper, rlimit, us, sign`；`self` 在它们
 * 之前多出 `src, uid, vod, nid, rev`（前五个是定位与身份信息，不是签名本身）。腾讯的实现
 * 按顺序拼串校验，顺序变了就播不了。原始地址自带的 query 原样保留在签名参数之前。
 *
 * 为什么 `us` 每集单独随机、不与整部剧共用：`tencent` 模式下安全边界由 Dir 决定（同目录
 * 同签名可互用，这一格由 CDN 侧兜不住，只能靠目录本身）；`self` 模式由 uid/vod/nid 兜住。
 * 共用 `us` 只会让一部剧的每集地址长得一模一样，排查与 CDN 缓存都没法区分；每集随机的
 * 代价只是一次 `random_bytes(8)`。
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
        // `self` 模式的失效序号：运营在后台把它加一，此前下发的地址全部作废（复算对不上）。
        // 只进 `self` 的签名串；`tencent` 模式连读都不读——腾讯云按写死的串校验。
        private readonly int $rev = 0,
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

        // 失效序号缺省 0：老库没有这个键就按 0 算，签名串里多一段字面 `|0`；
        // 媒体服务按新契约复算，插件与媒体服务同时升级（见 docs/cdn.md 第 5 节）
        return new self($domain, $key, $type, max(0, Setting::int('cdn_sign_rev', 0)));
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
     * `$identity` 是"谁在看哪一集"：`self` 模式把它并进签名并写进 URL（见类注释）；
     * `tencent` 模式整条不用它——腾讯云的签名串写死，多一个参数就验不过。
     *
     * `$now` 只给测试固定时间用，线上调用不传。
     */
    public function signedUrl(
        string $rawUrl,
        PlayIdentity $identity,
        int $exper = 0,
        ?int $now = null
    ): ?string {
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

        $self = $this->type === self::TYPE_SELF;
        // 空串 = 不带 src（tencent 模式）；self 模式取不到 host 就没法回源，直接不给地址
        $src = $self ? self::sourceHost($parts) : '';
        if ($self && $src === '') {
            return null;
        }

        // path 一定以 `/` 开头，strrpos 至少命中 0，取不到时兜底成根目录
        $slash = strrpos($path, '/');
        $dir = $slash === false ? '/' : substr($path, 0, $slash + 1);

        $t = dechex(($now ?? time()) + self::TOKEN_TTL);
        $exper = max(0, $exper);
        $us = bin2hex(random_bytes(self::US_BYTES));
        // 身份段只有 self 模式拼：tencent 加任何一项都会被腾讯云判为签名不匹配
        $binding = $self ? $identity->signaturePart() . '|' . $this->rev : '';
        $sign = md5($this->key . $dir . $t . $exper . self::RLIMIT . $us . $src . $binding);

        $query = (string)($parts['query'] ?? '');
        $url = $this->domain . $path . ($query === '' ? '' : '?' . $query);

        return $url . ($query === '' ? '?' : '&')
            . ($src === '' ? '' : 'src=' . rawurlencode($src) . '&')
            . ($self ? $identity->queryPart() . '&rev=' . $this->rev . '&' : '')
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
