package com.starveil.feature.player

import android.content.Context
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
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object VideoCache {

    private const val DIR = "video-cache"
    private const val MAX_BYTES = 300L * 1024 * 1024

    @Volatile
    private var instance: SimpleCache? = null

    /**
     * 计算缓存键时**必须**剥掉的 CDN 签名参数。
     *
     * 服务端接通 CDN 播放后，每次下发的地址都带这一组：`us` 随机、`t` 每 6 小时滚动，
     * 其余三个跟着一起变。它们只证明"这次请求签过名"，不参与资源标识。
     */
    val SIGNATURE_PARAMS: Set<String> = setOf("t", "exper", "rlimit", "us", "sign")

    @OptIn(UnstableApi::class)
    fun get(context: Context): Cache = instance ?: synchronized(this) {
        instance ?: SimpleCache(
            File(context.applicationContext.cacheDir, DIR),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
        ).also { instance = it }
    }
}

/**
 * 把一条播放地址规范化成 [VideoCache] 的键。
 *
 * **为什么不能直接用完整 URL**：`SimpleCache` 默认拿 `DataSpec` 里的完整 URL 当键，
 * 而签名地址每次下发都不同（见 [VideoCache.SIGNATURE_PARAMS]）。同一集、同一片，
 * 只要签名换了就是一条"新资源"：缓存永远命不中，磁盘只增不减，
 * 300MB 的 LRU 被逼着不停淘汰——缓存等于白做，还白写 300MB。
 *
 * **只剥签名那几个参数**，其它 query 原样保留：源站用 query 区分文件的情况
 * （`?id=`、`?token=`）是真的资源标识，粗暴 `substringBefore('?')` 会把不同分片
 * 并成同一份缓存，那是错数据，比缓存不命中严重得多。
 *
 * 参数名按大小写敏感比对：多剥一个认不出的参数就等于把两份内容并成一份，宁可少剥。
 */
internal fun videoCacheKey(url: String): String {
    val queryStart = url.indexOf('?')
    if (queryStart < 0) return url
    val fragmentStart = url.indexOf('#', queryStart).let { if (it < 0) url.length else it }
    val kept = url.substring(queryStart + 1, fragmentStart)
        .split('&')
        .filter { it.isNotEmpty() && it.substringBefore('=') !in VideoCache.SIGNATURE_PARAMS }
    val base = url.substring(0, queryStart)
    val fragment = url.substring(fragmentStart)
    return if (kept.isEmpty()) base + fragment else base + "?" + kept.joinToString("&") + fragment
}
