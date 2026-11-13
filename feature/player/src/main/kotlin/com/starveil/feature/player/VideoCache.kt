package com.starveil.feature.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * 视频分片缓存（Media3 [SimpleCache]）。
 *
 * 为什么不用 OkHttp 的 `Cache`：那是 HTTP 缓存，按整条响应缓存；m3u8 分片动辄几百 KB
 * 且常被 range 请求切开，OkHttp 只能整存整取，命中率有限。SimpleCache 是 Media3 为
 * 流式播放做的，按 span 存、支持跨集复用、换集回看直接命中磁盘。
 *
 * **必须是进程级单例**：同一个目录被两个 SimpleCache 同时打开会抛
 * "Another SimpleCache instance uses the folder"。播放页与竖屏流可能各自持有一个
 * Media3Player，但缓存目录只能有一个主人。
 *
 * 不提供 release：缓存要在整个进程生命周期内存活，下次起播、换集都靠它命中；
 * 关掉再开会把已缓存的分片索引丢一次，下次起播反而更慢。
 *
 * **只放分片，不放清单**。清单（m3u8/mpd）的时效是分钟级，缓存下来的后果是
 * 客户端拿着过期的分片签名去请求，源站回 403，表现成"这条线路被源站拒绝了"。
 * 分流由 [SplitDataSourceFactory] 在 DataSource 层完成，不在这里判。
 */
@OptIn(UnstableApi::class)
internal object VideoCache {

    private const val DIR = "video-cache"
    private const val MAX_BYTES = 300L * 1024 * 1024

    @Volatile
    private var instance: SimpleCache? = null

    /**
     * 计算缓存键时必须剥掉的 CDN 签名参数。
     *
     * 服务端接通 CDN 播放后，每次下发的地址都带这一组：`us` 随机、`t` 每 6 小时滚动，
     * 其余几个跟着一起变。它们只证明"这次请求签过名"，不参与资源标识。
     *
     * 注意：这张表只对**非分片**地址生效。分片走 [videoCacheKey] 里的路径优先分支，
     * 不查这张表——新接一个源不需要往这里加参数。
     */
    val SIGNATURE_PARAMS: Set<String> = setOf(
        // 星幕 CDN
        "t", "exper", "rlimit", "us", "sign",
        // 通用时间戳 / 随机量
        "timestamp", "ts", "expires", "exp", "st", "nonce", "rand", "rs", "ref",
        // 常见签名
        "auth_key", "sig", "signature", "wsSecret", "wsTime", "token", "key",
    )

    @OptIn(UnstableApi::class)
    fun get(context: Context): Cache = instance ?: synchronized(this) {
        instance ?: SimpleCache(
            File(context.applicationContext.cacheDir, DIR),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
        ).also { instance = it }
    }
}

/**
 * 播放资源的种类。
 *
 * 这个分类是缓存策略的基础：清单不能缓存，分片必须缓存。
 * 把两者混为一谈，就会出现"清了缓存才正常"的现象。
 */
internal enum class ResourceKind {

    /** m3u8 / mpd / manifest：内容随每次请求变，缓存了就是死链源。 */
    MANIFEST,

    /** ts / m4s / mp4 / aac：内容不可变，缓存价值最高。 */
    SEGMENT,

    /** 判不出来。按 MANIFEST 处理——不缓存是安全的一侧。 */
    UNKNOWN,
}

private val MANIFEST_EXT = setOf("m3u8", "m3u", "mpd", "ism", "ismc", "f4m", "m3u8s")

private val SEGMENT_EXT = setOf(
    "ts", "m4s", "m4a", "m4v", "mp4", "aac", "key", "cmfv", "cmfa",
    "webm", "vtt", "srt", "jpg", "png", // HLS 的 I-FRAME 与字幕 / 缩略图
)

/**
 * 判定一条播放地址是清单还是分片。
 *
 * 只能看路径，不能看 query：query 里全是签名与时效，与资源种类无关。
 *
 * 判定不了就返回 [ResourceKind.UNKNOWN]，调用方按"不缓存"处理。
 * 宁可少缓存（最差是没提速），也不要多缓存（缓存了死数据）。
 */
internal fun classifyPlaybackResource(uri: Uri): ResourceKind =
    classifyPlaybackResource(uri.toString())

/** 纯 Kotlin 版本：JVM 单测造不出 [Uri]，见 [videoCacheKey] 的说明。 */
internal fun classifyPlaybackResource(url: String): ResourceKind {
    val path = parseCacheUrl(url).path.lowercase()
    val ext = path.substringAfterLast('.', "")
    return when {
        ext in MANIFEST_EXT -> ResourceKind.MANIFEST
        ext in SEGMENT_EXT -> ResourceKind.SEGMENT
        // 无扩展名时看路径里的特征词，这是少数 CDN 的做法
        path.contains("manifest") || path.contains("playlist") -> ResourceKind.MANIFEST
        else -> ResourceKind.UNKNOWN
    }
}

/**
 * 把一条播放地址规范化成 [VideoCache] 的键。
 *
 * **核心原则：分片的路径本身就是内容身份，query 一个都不留。**
 *
 * 以 dujia 的真实分片为例：
 *
 * ```
 * .../1920/c5c47bf54673fc8c9ee4b1660206dd50.ts?sign=cbef8036&timestamp=1794498739&ref=0
 * ```
 *
 * `c5c47bf54673fc8c9ee4b1660206dd50` 是 32 位内容哈希，同一分片永远是同一个文件名；
 * query 里的 sign / timestamp / ref / name 全是"这次请求签过名"的凭据，name 甚至和
 * 文件名重复。把它们算进键，签名一刷新键就变，缓存永远命不中，300MB 的 LRU 被逼着
 * 不停写入和淘汰——缓存等于白做，还白占磁盘。
 *
 * 为什么不沿用"剥掉已知签名参数"的思路：那是按平台枚举，每接一个新源就要补一次参数名，
 * 漏一个就退化成永不命中。而 HLS/DASH 规范本来就把分片文件名定成内容哈希，
 * 按路径取键是一个源无关的通用规则。
 *
 * 非分片资源仍然走白名单 [cacheableParamsFor]：路径不保证唯一（`?id=123` 这种是常见设计），
 * 所以只保留显式登记过的参数，默认一个都不留。多留一个资源标识参数会把两份内容并成
 * 一份，那是错数据，比缓存不命中严重得多。
 */
internal fun videoCacheKey(uri: Uri): String = videoCacheKey(uri.toString())

/**
 * [videoCacheKey] 的字符串版本。
 *
 * 保留它有两个用处：一是 `SimpleCache` 的 `CacheKeyFactory` 与预取时 `DataSpec` 里的键
 * 拿同一段文本算出来，中间不经过一次 Uri 解析；二是本模块的
 * 单元测试跑在纯 JVM 上（`unitTests.isReturnDefaultValues = true`，刻意不引 Robolectric），
 * 造不出 `android.net.Uri`，键规则只能测这个重载。
 */
internal fun videoCacheKey(url: String): String {
    val parsed = parseCacheUrl(url)
    val kept = if (classifyPlaybackResource(url) == ResourceKind.SEGMENT) {
        emptyList()
    } else {
        keptParams(parsed)
    }
    return if (kept.isEmpty()) parsed.base else "${parsed.base}?${kept.joinToString("&")}"
}

/** 非分片资源允许参与缓存键的 query 参数，默认一个都不留。 */
private fun keptParams(parsed: CacheUrl): List<String> {
    val query = parsed.query ?: return emptyList()
    val allowed = cacheableParamsFor(parsed.host)
    return query.split('&')
        .filter { it.isNotEmpty() && it.substringBefore('=') !in VideoCache.SIGNATURE_PARAMS }
        .filter { it.substringBefore('=') in allowed }
}

/**
 * 每个域名允许参与缓存键的 query 参数。
 *
 * 默认空。空的意思是"这个域名的资源不靠 query 区分"，这比凭空列一串签名参数安全得多：
 * 漏掉一个签名参数只导致缓存不命中（白写磁盘），误留一个资源标识参数会导致两份内容
 * 并成一份（播错内容）。
 *
 * 只有当确认某个源站用 query 区分不同文件时才登记，例如
 * `"img.example.com" to setOf("id", "size")`。这张表可以接 config 模块远程下发。
 */
private val cacheableParams: Map<String, Set<String>> = emptyMap()

internal fun cacheableParamsFor(host: String?): Set<String> =
    cacheableParams[host] ?: emptySet()

/**
 * 拆开后的播放地址。
 *
 * 只保留算缓存键要用的部分：fragment 不参与（它不被发到服务端），
 * userinfo 也不参与（同一台主机上的资源身份与凭据无关）。
 */
private class CacheUrl(val scheme: String?, val host: String?, val path: String, val query: String?) {

    /** `scheme://host` + path，不含 query 与 fragment。 */
    val base: String = buildString {
        scheme?.let { append(it).append("://") }
        host?.let { append(it) }
        append(path)
    }
}

/**
 * 把地址拆成算键要用的几段。
 *
 * 自己拆而不是走 [Uri]，是为了让键规则能在纯 JVM 单测里跑（见 [videoCacheKey]）。
 * 解析只做"找 ? 与 #、找 ://、找 host 后的第一个 /"，覆盖播放地址的实际形态即可；
 * 拆不出来的一律当成没有 scheme/host 的路径。
 */
private fun parseCacheUrl(url: String): CacheUrl {
    val end = url.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) url.length else it }
    val path = url.substring(0, end)
    val query = url.substringBefore('#').substringAfter('?', "").ifEmpty { null }
    val schemeEnd = path.indexOf("://")
    if (schemeEnd <= 0) return CacheUrl(null, null, path, query)
    val rest = path.substring(schemeEnd + 3)
    val hostEnd = rest.indexOf('/').let { if (it < 0) rest.length else it }
    return CacheUrl(
        scheme = path.substring(0, schemeEnd),
        host = rest.substring(0, hostEnd),
        path = rest.substring(hostEnd),
        query = query,
    )
}
