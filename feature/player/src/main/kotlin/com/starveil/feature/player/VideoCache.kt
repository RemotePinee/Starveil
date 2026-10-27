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

    @OptIn(UnstableApi::class)
    fun get(context: Context): Cache = instance ?: synchronized(this) {
        instance ?: SimpleCache(
            File(context.applicationContext.cacheDir, DIR),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
        ).also { instance = it }
    }
}
