package com.starveil.feature.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.starveil.core.hongguo.HongguoConfig
import com.starveil.core.hongguo.HongguoRelay
import com.starveil.core.hongguo.Mp4SampleTable
import com.starveil.core.hongguo.SpadeKey
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 红果加密片源的入口。
 *
 * 包住真正的上游 DataSource，在 open() 时判断这条地址要不要解密：
 *   - 开关没开 / 规则没命中  -> 原样透传，普通 mp4、m3u8、直播都不受影响
 *   - 命中                   -> 拉材料、解析 sample 表，边读边解
 *
 * 之所以把判断放在 open()：DataSource.Factory 创建时拿不到 URL，
 * URL 是 open(dataSpec) 才给的。
 */
/** 预热时不需要真的读数据，占位用。 */
@androidx.annotation.OptIn(UnstableApi::class)
private object NoopDataSource : DataSource {
    override fun open(dataSpec: DataSpec): Long = 0
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
    override fun addTransferListener(transferListener: TransferListener) = Unit
    override fun getUri(): Uri? = null
    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
    override fun close() = Unit
}

@androidx.annotation.OptIn(UnstableApi::class)
internal class HongguoGateDataSource(
    private val upstream: DataSource,
    private val config: HongguoConfig,
    private val headers: Map<String, String>,
    private val client: OkHttpClient,
) : DataSource {

    private var inner: DataSource? = null
    /** open() 的时刻，用来算整条链路（材料 + 头部 + 解析）的总耗时。 */
    @Volatile
    private var openedAt = 0L

    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri.toString()
        val enabled = config.enabled
        val hit = config.matches(url)
        openedAt = SystemClock.elapsedRealtime()
        // 只留布尔与 host：url 带签名，按 docs/security.md 第 8 节不得进日志
        PlayerLog.warn {
            "open enabled=$enabled patterns=${config.patterns} hit=$hit host=${PlayerLog.hostOf(url)}"
        }
        val decrypted = if (hit) prepareFor(url) else null
        if (hit && decrypted == null) PlayerLog.warn { "prepare 失败，按透传处理（画面会黑）" }
        inner = if (decrypted != null) HongguoCencDataSource(upstream, decrypted.first, decrypted.second) else upstream
        return (inner ?: upstream).open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (inner ?: upstream).read(buffer, offset, length)

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() {
        (inner ?: upstream).close()
        inner = null
    }

    /**
     * 拉材料 + 解析 moov。失败返回 null，走原样透传（宁可黑屏也不误伤）。
     *
     * 预热线程和 open() 会先后调到同一个地址：谁先到谁下载，后到的等结果，
     * 否则两边各下一遍头部，白白多花一倍时间。
     */
    fun prepareFor(relayUrl: String): Pair<ByteArray, Mp4SampleTable>? {
        val relay = HongguoRelay.parse(relayUrl) ?: return null
        val cacheKey = "${relay.first}/${relay.second}"
        return memHit(cacheKey) ?: diskHit(cacheKey) ?: prepareShared(relayUrl, cacheKey)
    }

    private fun memHit(cacheKey: String): Pair<ByteArray, Mp4SampleTable>? = cache[cacheKey]

    /** 磁盘里已有这一集：直接读，跳过整个下载与解析。 */
    private fun diskHit(cacheKey: String): Pair<ByteArray, Mp4SampleTable>? {
        val entry = appContext?.let { HongguoDiskCache.load(it, cacheKey) }
        return entry?.let {
            val ready = it.key to it.table
            cache[cacheKey] = ready
            PlayerLog.warn { "磁盘命中 $cacheKey（跳过下载）" }
            ready
        }
    }

    /** 抢同一个地址的准备权：已经有别的线程在准备就等它的结果，不重复下载。 */
    private fun prepareShared(
        relayUrl: String,
        cacheKey: String,
    ): Pair<ByteArray, Mp4SampleTable>? {
        val fresh = CompletableFuture<Pair<ByteArray, Mp4SampleTable>?>()
        val existing = inFlight.putIfAbsent(cacheKey, fresh)
        if (existing != null) {
            return runCatching { existing.get(PREPARE_TIMEOUT_SEC, TimeUnit.SECONDS) }.getOrNull()
        }
        return try {
            // 整条准备链路（含落缓存）的异常都收敛成 null：宁可透传黑屏，也不能把异常
            // 抛回 open()，否则一条坏片源会直接崩掉播放页。
            val ready = runCatching {
                fetchAndParse(relayUrl, cacheKey).also { if (it != null) remember(cacheKey, it) }
            }.getOrNull()
            fresh.complete(ready)
            ready
        } finally {
            inFlight.remove(cacheKey)
        }
    }

    /** 内存给下一次 open，磁盘给下一次进程。 */
    private fun remember(cacheKey: String, ready: Pair<ByteArray, Mp4SampleTable>) {
        cache[cacheKey] = ready
        appContext?.let { ctx ->
            HongguoDiskCache.save(ctx, cacheKey, ready.first, ready.second)
            HongguoDiskCache.trim(ctx)
        }
    }

    private fun fetchAndParse(
        relayUrl: String,
        cacheKey: String,
    ): Pair<ByteArray, Mp4SampleTable>? {
        PlayerLog.warn { "开始准备 $cacheKey" }
        // 准备链路上的任何异常都收敛成 null：播放器宁可黑屏也不能被解密层带崩
        return runCatching { parseHead(relayUrl) }
            .onFailure { PlayerLog.warn(it) { "prepare 异常" } }
            .getOrNull()
    }

    /** 材料 -> 头部 -> sample 表。任一环拿不到就返回 null。 */
    private fun parseHead(relayUrl: String): Pair<ByteArray, Mp4SampleTable>? {
        val source = sourceOf(relayUrl) ?: return null
        val head = downloadHead(source.second)
        if (head == null) {
            PlayerLog.warn { "下载头部失败: host=${PlayerLog.hostOf(source.second)}" }
        } else {
            PlayerLog.warn { "头部 ${head.size} 字节" }
        }
        val table = head?.let { tableOf(it) }
        val elapsedMs = SystemClock.elapsedRealtime() - openedAt
        if (table != null) {
            PlayerLog.warn { "sample 表 ${table.samples.size} 条，解密就绪（open 至今 ${elapsedMs}ms）" }
        }
        return table?.let { source.first to it }
    }

    /** 解析 moov；解不出来说明这集的 stsz/stco/stsc/senc 不全。 */
    private fun tableOf(head: ByteArray): Mp4SampleTable? {
        val table = Mp4SampleTable.parse(head)
        if (table == null) PlayerLog.warn { "moov 解析失败（stsz/stco/stsc/senc 缺失？）" }
        return table
    }

    /** 材料接口 -> (派生的 key, 播放地址)；字段缺失时把断在哪一步写进日志。 */
    private fun sourceOf(relayUrl: String): Pair<ByteArray, String>? {
        val json = fetchMaterialJson(relayUrl)
        val material = Material(
            spadeA = json?.optString("spade_a")?.takeIf { it.isNotBlank() },
            mainUrl = json?.optString("main_url")?.takeIf { it.isNotBlank() },
        )
        if (json == null || material.ready() == null) logMaterialProblem(relayUrl, json, material)
        return material.ready()
    }

    /** 只把材料取进缓存，不解析 moov。 */
    fun warmMaterial(relayUrl: String) {
        fetchMaterialJson(relayUrl)
    }

    private fun fetchMaterialJson(relayUrl: String): JSONObject? {
        val cacheKey = relayUrl.substringBefore('?')
        return cachedMaterial(cacheKey)
            ?: HongguoRelay.materialUrl(relayUrl)?.let { fetchMaterial(it, cacheKey) }
    }

    /**
     * 缓存里的材料，未过期才算命中。
     *
     * 来回滑动会反复要同一集的材料，而 spade_a 与地址在几分钟内都有效，
     * 缓存一下能省掉每次 0.3~0.5 秒的网络往返。
     */
    private fun cachedMaterial(cacheKey: String): JSONObject? = synchronized(materialCache) {
        materialCache[cacheKey]
            ?.takeIf { (at, _) -> SystemClock.elapsedRealtime() - at < MATERIAL_TTL_MS }
            ?.second
    }

    private fun fetchMaterial(target: String, cacheKey: String): JSONObject? {
        val t0 = SystemClock.elapsedRealtime()
        val body = readBody(target)
        return body?.let {
            PlayerLog.warn { "材料请求 ${SystemClock.elapsedRealtime() - t0}ms" }
            runCatching { JSONObject(it) }.getOrNull()
        }?.also { json ->
            synchronized(materialCache) {
                materialCache[cacheKey] = SystemClock.elapsedRealtime() to json
            }
        }
    }

    /** 读响应体；非 2xx 或空 body 都按"没拿到材料"处理。 */
    private fun readBody(target: String): String? {
        val req = Request.Builder().url(target).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        return client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    }

    /**
     * 取文件头部用来解析 moov。
     *
     * 先只请求 1MB：绝大多数集子的 moov 都在这个范围内，少下 1MB 就是少等一两秒。
     * 解析不出来（moov 特别大）再补到 2MB —— 用一次多余的请求换掉每集固定多等的时间。
     */
    private fun downloadHead(mainUrl: String): ByteArray? {
        warmUp(mainUrl)
        val small = rangeRequest(mainUrl, HEAD_BYTES_SMALL)
        if (small != null && Mp4SampleTable.parse(small) != null) return small
        return rangeRequest(mainUrl, HEAD_BYTES)
    }

    /**
     * 预先建立到 CDN 的连接。
     *
     * 红果每集走的是不同的 CDN 子域名，连接池里没有可复用的连接，
     * 每条新连接都要完整走一遍 DNS + TCP + TLS（稳定在几百毫秒）。
     * 先发一个小请求把连接备好，后面并行的 4 段就能直接复用。
     */
    private fun warmUp(mainUrl: String) {
        Thread {
            runCatching {
                val req = Request.Builder().url(mainUrl)
                    .header("Range", "bytes=0-0")
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .build()
                client.newCall(req).execute().close()
            }
        }.apply { isDaemon = true; name = "hongguo-warmup" }.start()
    }

    /**
     * 分段并行下载头部。
     *
     * 单条 Range 请求在手机上实测要 0.5~2.8 秒，波动很大；切成 4 段同时拉能
     * 吃满带宽，也把单条连接的抖动摊薄。任何一段失败就退回串行。
     */
    private fun rangeRequest(mainUrl: String, bytes: Int): ByteArray? {
        val part = bytes / HEAD_PARTS
        val parts = arrayOfNulls<ByteArray>(HEAD_PARTS)
        val latch = CountDownLatch(HEAD_PARTS)
        for (i in 0 until HEAD_PARTS) {
            val start = i * part
            val end = if (i == HEAD_PARTS - 1) bytes - 1 else start + part - 1
            Thread {
                runCatching { parts[i] = downloadPart(mainUrl, start, end) }
                latch.countDown()
            }.apply { isDaemon = true; name = "hongguo-head-$i" }.start()
        }
        latch.await(HEAD_TIMEOUT_SEC, TimeUnit.SECONDS)

        val out = ByteArrayOutputStream(bytes)
        for (p in parts) {
            if (p == null) return null
            out.write(p)
        }
        return out.toByteArray()
    }

    /** 拉一段 Range；失败返回 null，由 [rangeRequest] 整体判失败。 */
    private fun downloadPart(mainUrl: String, start: Int, end: Int): ByteArray? {
        val req = Request.Builder().url(mainUrl)
            .header("Range", "bytes=$start-$end")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        return client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.bytes() else null
        }
    }

    companion object {
        /**
         * 预取线程池。
         * 每次 new Thread 在系统繁忙时会被推迟好几秒；但并发也不能太高——
         * 预取和播放流抢同一条带宽，3 个并发时实测播放那一路会掉到 2~3 秒。
         */
        private val prefetchPool = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
            Thread(r, "hongguo-prefetch").apply { isDaemon = true }
        }

        /** 共享的下载客户端：预热是后台行为，没必要占用播放器那条连接的池子。 */
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .connectionPool(okhttp3.ConnectionPool(8, 10, TimeUnit.MINUTES))
                .retryOnConnectionFailure(false)
                .build()
        }

        /** 播放前预热：提前把材料与头部取下来，open() 时直接命中缓存。 */
        fun prefetch(url: String?, config: HongguoConfig, headers: Map<String, String>) {
            val (target, key) = resolvePrefetch(url, config) ?: return
            if (cache.containsKey(key)) return
            prefetchPool.execute {
                runCatching {
                    HongguoGateDataSource(NoopDataSource, config, headers, sharedClient).prepareFor(target)
                }
            }
        }

        /**
         * 只预热材料（几百字节），不下载头部。
         *
         * 材料请求走中转，跨境往返一次 150~1600ms，是起播时间里的主要抖动来源；
         * 提前拿到就能把这部分藏掉，代价几乎为零。头部留给 [prefetch] 单独控制。
         */
        fun prefetchMaterial(url: String?, config: HongguoConfig, headers: Map<String, String>) {
            val (target, key) = resolvePrefetch(url, config) ?: return
            if (cache.containsKey(key)) return
            prefetchPool.execute {
                runCatching {
                    HongguoGateDataSource(NoopDataSource, config, headers, sharedClient).warmMaterial(target)
                }
            }
        }

        /** 红果地址 -> (原始地址, 缓存 key)；开关没开或规则没命中返回 null。 */
        private fun resolvePrefetch(url: String?, config: HongguoConfig): Pair<String, String>? =
            url?.takeIf { config.matches(it) }?.let { target ->
                HongguoRelay.parse(target)?.let { relay -> target to "${relay.first}/${relay.second}" }
            }

        /** moov 实测 782KB，1MB 是上限；先要 800KB 就够绝大多数集。 */
        private const val HEAD_BYTES_SMALL = 800 * 1024

        /**
         * 头部下载切几段并行。
         * 实测 1 段 2.8s、4 段 1.2s；手机网络下多开几条能进一步摊薄单连接抖动。
         * 上限受连接池约束：池子 8 条，留 2 条给播放流。
         */
        private const val HEAD_PARTS = 6
        private const val HEAD_TIMEOUT_SEC = 20L
        /** 材料缓存有效期：地址 30 分钟内有效，取 5 分钟很安全。 */
        private const val MATERIAL_TTL_MS = 5 * 60 * 1000L

        /**
         * 材料缓存，按访问顺序淘汰。
         *
         * 原来是裸 ConcurrentHashMap：过期的项只被同 key 覆盖，从不清除，
         * 而 key 是「剧/集」——一路刷下去每集都留一条 JSONObject 在里面，
         * 刷得越多占得越多，最后表现就是越刷越卡。
         * 上限取 64：够覆盖前后翻页的范围，又不会无限涨。
         */
        private val materialCache = object : LinkedHashMap<String, Pair<Long, JSONObject>>(
            16, 0.75f, true
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Pair<Long, JSONObject>>?,
            ) = size > MATERIAL_CACHE_ENTRIES
        }
        private const val MATERIAL_CACHE_ENTRIES = 64
        private const val PREPARE_TIMEOUT_SEC = 30L

        /** 正在准备中的地址：避免预热与 open() 重复下载同一份头部。 */
        private val inFlight = ConcurrentHashMap<String, CompletableFuture<Pair<ByteArray, Mp4SampleTable>?>>()
        private const val HEAD_BYTES = 2 * 1024 * 1024

        /**
         * 同一集的 key 与 sample 表不会变，缓存在内存里。
         * 用 LRU 而不是裸 HashMap：一集 52KB，短剧一刷几千集就吃掉几百 MB。
         */
        private val cache = object : LinkedHashMap<String, Pair<ByteArray, Mp4SampleTable>>(
            16, 0.75f, true
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Pair<ByteArray, Mp4SampleTable>>?,
            ) = size > MEMORY_CACHE_ENTRIES
        }
        private const val MEMORY_CACHE_ENTRIES = 24

        /** 磁盘读写要 Context，由 VideoHttp 在构造时写入。 */
        @Volatile
        var appContext: android.content.Context? = null
    }
}

/** 材料里我们关心的两样东西：spade_a 与播放地址。 */
private class Material(val spadeA: String?, val mainUrl: String?) {
    val key: ByteArray? = spadeA?.let { SpadeKey.derive(it) }

    fun ready(): Pair<ByteArray, String>? =
        if (key == null || mainUrl == null) null else key to mainUrl
}

/** 对着后台的采集状态排查用：缺哪个字段就报哪个。 */
private fun logMaterialProblem(relayUrl: String, json: JSONObject?, material: Material) {
    val reason = when {
        json == null -> "材料接口无响应: host=${PlayerLog.hostOf(HongguoRelay.materialUrl(relayUrl))}"
        material.spadeA == null -> "材料里没有 spade_a（这集没采过？）"
        material.mainUrl == null -> "材料里没有 main_url"
        else -> "spade_a 派生 key 失败"
    }
    PlayerLog.warn { reason }
}
