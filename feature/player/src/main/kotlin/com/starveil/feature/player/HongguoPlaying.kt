package com.starveil.feature.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.starveil.core.hongguo.HongguoConfig
import com.starveil.core.hongguo.HongguoRelay
import com.starveil.core.hongguo.SpadeKey
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
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

    /** 头部下载与解析：只管存取字节，材料与缓存留在本类。 */
    private val headFetcher = HongguoHeadFetcher(client, headers)

    /**
     * 开始准备的时刻，用来算整条链路（材料 + 头部 + 解析）的总耗时。
     *
     * 必须从构造时就开始算：预取走的是 `prepareFor()`，根本不调 open()，
     * 原来初值 0 让日志打出 `open 至今 142395340ms`——那是开机以来的时长，不是准备耗时。
     */
    @Volatile
    private var openedAt = SystemClock.elapsedRealtime()

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
        inner = if (decrypted != null) HongguoCencDataSource(upstream, decrypted.key, decrypted.table) else upstream
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
    fun prepareFor(relayUrl: String): HongguoPrepared? {
        val relay = HongguoRelay.parse(relayUrl) ?: return null
        val cacheKey = "${relay.first}/${relay.second}"
        // 三级回退：内存 LRU -> 磁盘 -> 下载解密。内存被淘汰不等于要重下：
        // 磁盘里那份是同一集的表，读 70KB 文件比再拉 800KB 头部再解析快一个数量级。
        return HongguoTableCache.get(cacheKey) ?: diskHit(cacheKey) ?: prepareShared(relayUrl, cacheKey)
    }

    /** 磁盘里已有这一集：直接读，跳过整个下载与解析。 */
    private fun diskHit(cacheKey: String): HongguoPrepared? {
        val entry = appContext?.let { HongguoDiskCache.load(it, cacheKey) }
        val ready = entry?.let { HongguoPrepared(it.key, it.table) }
        if (ready != null) {
            HongguoTableCache.put(cacheKey, ready)
            PlayerLog.warn { "磁盘命中 $cacheKey（跳过下载），内存 ${HongguoTableCache.describe()}" }
        }
        return ready
    }

    /** 抢同一个地址的准备权：已经有别的线程在准备就等它的结果，不重复下载。 */
    private fun prepareShared(
        relayUrl: String,
        cacheKey: String,
    ): HongguoPrepared? {
        val fresh = CompletableFuture<HongguoPrepared?>()
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
            // 成功、失败、被取消（含准备途中 OOM）都必须走到这里：
            // 一个永远不 complete 的 future 会让等这一集的所有人白等满 30 秒，
            // 而留在表里的那一项还攥着 key 与它引用的整张表不放。
            fresh.complete(null)
            inFlight.remove(cacheKey)
        }
    }

    /** 内存给下一次 open，磁盘给下一次进程。 */
    private fun remember(cacheKey: String, ready: HongguoPrepared) {
        HongguoTableCache.put(cacheKey, ready)
        appContext?.let { ctx ->
            HongguoDiskCache.save(ctx, cacheKey, ready.key, ready.table)
            HongguoDiskCache.trim(ctx)
        }
        PlayerLog.warn { "内存缓存 ${HongguoTableCache.describe()}" }
    }

    private fun fetchAndParse(
        relayUrl: String,
        cacheKey: String,
    ): HongguoPrepared? {
        PlayerLog.warn { "开始准备 $cacheKey" }
        // 准备链路上的任何异常都收敛成 null：播放器宁可黑屏也不能被解密层带崩
        return runCatching { parseHead(relayUrl) }
            .onFailure { PlayerLog.warn(it) { "prepare 异常" } }
            .getOrNull()
    }

    /** 材料 -> 头部 -> sample 表。任一环拿不到就返回 null。 */
    private fun parseHead(relayUrl: String): HongguoPrepared? {
        val source = sourceOf(relayUrl) ?: return null
        val table = headFetcher.fetch(source.second, openedAt)
        if (table == null) {
            PlayerLog.warn { "头部下载或 moov 解析失败: host=${PlayerLog.hostOf(source.second)}" }
        }
        return table?.let { HongguoPrepared(source.first, it) }
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

        /**
         * 播放前预热：提前把材料与头部取下来，open() 时直接命中内存或磁盘。
         *
         * 原来在这里先查一次 `cache.containsKey`：既是无锁访问 LRU，
         * 又会漏掉"内存没有、磁盘有"的情形——那两种情况现在都交给
         * [prepareFor] 的三级回退统一判断。
         */
        fun prefetch(url: String?, config: HongguoConfig, headers: Map<String, String>) {
            val target = resolvePrefetch(url, config) ?: return
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
            val target = resolvePrefetch(url, config) ?: return
            prefetchPool.execute {
                runCatching {
                    HongguoGateDataSource(NoopDataSource, config, headers, sharedClient).warmMaterial(target)
                }
            }
        }

        /** 红果地址 -> 原始地址；开关没开或规则没命中返回 null。 */
        private fun resolvePrefetch(url: String?, config: HongguoConfig): String? =
            url?.takeIf { config.matches(it) && HongguoRelay.parse(it) != null }

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
        private val inFlight = ConcurrentHashMap<String, CompletableFuture<HongguoPrepared?>>()

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
