package com.starveil.feature.player

import android.content.Context
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource

/**
 * 播放用的 HTTP 层：连接复用 + 磁盘缓存 + "下一集预取"。
 *
 * 单独成文件有两个原因：
 * 1. `Media3Player` 已经贴着 450 行闸门，播放器不该再背着网络细节；
 * 2. 这套东西（缓存目录、TTL、预取策略）与播放状态机无关，改它不应碰播放逻辑。
 *
 * 缓存策略：资源站的 m3u8/分片一般不带 `Cache-Control`，OkHttp 默认不缓存，
 * 所以这里给播放响应补一个短 TTL——清单 10 分钟、分片 3 分钟，只覆盖一次连滑，
 * 不长期占盘。
 */
internal class VideoHttp(private val context: Context) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(4, 5, TimeUnit.MINUTES))
        .cache(Cache(File(context.cacheDir, CACHE_DIR), CACHE_BYTES))
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            // 清单缓存 PLAYLIST_TTL 秒：解析站下发的 m3u8 里分片地址常带短时效签名，
            // 缓存太久会拿到过期地址、播放直接报「源站拒绝」。
            // 但完全 no-store 也有代价——同一集切后台回来、切清晰度都会重新拉一遍清单，
            // 每次 300-500ms。30 秒是个安全窗口：签名不会在这么短的间隔内失效，
            // 而重复 prepare 全落在这个区间里。
            val response = chain.proceed(request)
            val ttl = if (request.url.encodedPath.endsWith(HLS_SUFFIX)) PLAYLIST_TTL else SEGMENT_TTL
            response.newBuilder().header("Cache-Control", "public, max-age=$ttl").build()
        }
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var primedUrl: String? = null

    /**
     * 每集新建一个 Factory（要带该集的请求头），但底层 client、连接池与分片缓存是共享的。
     *
     * 外面套一层 [CacheDataSource]：Media3 先查 [VideoCache]，未命中才走网络。
     * 换集回看、拖动重播同一片都直接命中磁盘，不必重新下载。
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun dataSource(headers: Map<String, String>, userAgent: String?): DataSource.Factory {
        val upstream = OkHttpDataSource.Factory(client).apply {
            userAgent?.let { setUserAgent(it) }
            if (headers.isNotEmpty()) setDefaultRequestProperties(headers)
        }
        return CacheDataSource.Factory()
            .setCache(VideoCache.get(context))
            .setUpstreamDataSourceFactory(upstream)
            // 缓存读坏了就退回网络，不让一个坏分片把整集卡死
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * 预取清单与前几个分片。
     *
     * 同一地址只做一次：页面每次状态变化都会调用。失败静默——预取只是提速，
     * 不该影响播放。
     */
    fun prefetch(url: String?, headers: Map<String, String>) {
        if (url.isNullOrBlank() || url == primedUrl) return
        primedUrl = url
        job?.cancel()
        job = scope.launch {
            runCatching {
                val targets = if (isHls(url)) firstSegments(url, headers) else listOf(url)
                targets.forEach { fetch(url = it, headers = headers) }
            }
        }
    }

    fun release() {
        job?.cancel()
        scope.cancel()
    }

    /** 拉清单并返回前几个分片的绝对地址。 */
    private fun firstSegments(playlistUrl: String, headers: Map<String, String>): List<String> {
        val text = fetch(playlistUrl, headers) ?: return emptyList()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .take(SEGMENT_PREFETCH)
            .mapNotNull { segment -> java.net.URI(playlistUrl).resolve(segment)?.toString() }
            .toList()
    }

    /** GET 一个地址并读空响应体（读空才会落进 OkHttp 缓存）；返回文本。 */
    private fun fetch(url: String, headers: Map<String, String>): String? {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        return runCatching {
            client.newCall(request).execute().use { response -> response.body?.string() }
        }.getOrNull()
    }

    private fun isHls(url: String): Boolean =
        url.substringBefore('?').endsWith(HLS_SUFFIX, ignoreCase = true)

    private companion object {
        const val HLS_SUFFIX = ".m3u8"

        /** 80MB 大约能放 2-4 集短剧的清单与首片。 */
        const val CACHE_DIR = "video-http"
        const val CACHE_BYTES = 80L * 1024 * 1024

        /** 清单缓存 30 秒：够覆盖同一次起播里的重复 prepare，又赶不上签名过期。 */
        const val PLAYLIST_TTL = 30
        const val SEGMENT_TTL = 180

        /** 只预取首片：短剧首帧靠它，取多了浪费流量。 */
        const val SEGMENT_PREFETCH = 2
    }
}
