package com.starveil.feature.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 播放用的 HTTP 层：连接复用 + 分片缓存 + "下一集预取"。
 *
 * 单独成文件有两个原因：
 * 1. `Media3Player` 已经贴着 450 行闸门，播放器不该再背着网络细节；
 * 2. 这套东西（缓存、预取策略）与播放状态机无关，改它不应碰播放逻辑。
 *
 * **缓存只有一层**：Media3 的 [VideoCache]（SimpleCache）。OkHttp 自己的磁盘 Cache
 * 已经去掉——它和 SimpleCache 存的是同一批分片，各存一份白占几十 MB，
 * 而且两套 TTL 各有各的失效时机，出问题要同时怀疑两条链路。
 * 预取也走同一个缓存（[CacheWriter]），起播时 Media3 能直接命中。
 */
internal class VideoHttp(private val context: Context) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        // 连接与读取都给到 15 秒：资源站首包偶尔要十几秒，8 秒会把还能连上的线路判死。
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(4, 5, TimeUnit.MINUTES))
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
                targets.forEach { cacheByWriter(it, headers) }
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

    /**
     * 把一条地址整个读进 [VideoCache]。
     *
     * 用 [CacheWriter] 而不是直接 GET：它负责分块写入与 span 登记，
     * 起播时 Media3 的 [CacheDataSource] 才命中得了同一份数据。
     * 直接 GET 只是把字节读进内存又丢掉，对缓存没有任何贡献。
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun cacheByWriter(url: String, headers: Map<String, String>) {
        val upstream = OkHttpDataSource.Factory(client).apply {
            if (headers.isNotEmpty()) setDefaultRequestProperties(headers)
        }
        // CacheWriter 要求 CacheDataSource 实体（不是 Factory 建出来的 DataSource 接口），
        // 所以这里直接构造：cache + 上游 + 不跳过缓存 + 不额外挂 sink。
        val source = CacheDataSource(
            VideoCache.get(context),
            upstream.createDataSource(),
            CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR,
        )
        val spec = DataSpec.Builder().setUri(Uri.parse(url)).build()
        runCatching { CacheWriter(source, spec, ByteArray(0), null).cache() }
    }

    /** 读一个地址的文本。只用来解析 m3u8 清单，不落缓存。 */
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

        /** 只预取首片：短剧首帧靠它，取多了浪费流量。 */
        const val SEGMENT_PREFETCH = 2
    }
}
