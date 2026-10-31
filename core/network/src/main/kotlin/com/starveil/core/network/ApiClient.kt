package com.starveil.core.network

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import com.starveil.core.model.ParseHostPolicy
import com.starveil.core.model.ParseRules
import com.starveil.core.model.PlayStream
import okhttp3.Request
import kotlinx.serialization.json.Json
import com.starveil.core.network.dto.ParseResultDto
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.starveil.core.network.mapper.toDomain

/**
 * 会话 Cookie 容器。
 *
 * 只在"响应 URL 精确等于登录路径"时保存 Cookie 的做法，
 * 导致注册后必须再登录一次；这里改成保存所有响应下发的 Cookie。
 *
 * 后端要求的七个 Cookie 里 user_check 是会话校验串，缺失会 403。
 * 持久化（DataStore）在 :core:data 完成，本类只负责内存态。
 */
class StarveilCookieJar : CookieJar {
    /**
     * host → 该 host 当前的全部 cookie。
     *
     * 写方是 OkHttp 的任意工作线程（`saveFromResponse`），读方是主线程
     * （`sessionSnapshot()` / 登录态刷新），所以用 [ConcurrentHashMap]：读不加锁。
     * 每次写入都是**整体替换一份不可变 List**，读到的永远是某一时刻的完整快照。
     */
    private val store = ConcurrentHashMap<String, List<Cookie>>()

    /**
     * 合并写入，**不能整体替换**。
     *
     * 旧写法 `store[host] = cookies` 会让任意一个下发 Set-Cookie 的响应（网关、PHPSESSID 等）
     * 把该 host 已有的会话 cookie 全冲掉：登录成功后随便再来一个响应，`v1.user/detail`
     * 就变成无 cookie 请求，后端回「请先登录」。同名 cookie 以最新下发的为准。
     */
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        // compute 按键原子：两个响应并发下发时，不会互相把对方刚合并好的 cookie 覆盖掉
        store.compute(url.host) { _, existing -> merge(existing.orEmpty(), cookies) }
    }

    /** 同名以最新下发的为准：先铺旧值再用新值覆盖；返回整体不可变的新 List。 */
    private fun merge(existing: List<Cookie>, incoming: List<Cookie>): List<Cookie> {
        val merged = LinkedHashMap<String, Cookie>(existing.size + incoming.size)
        existing.forEach { cookie -> merged[cookie.name] = cookie }
        incoming.forEach { cookie -> merged[cookie.name] = cookie }
        return merged.values.toList()
    }

    /**
     * 同 host 全量发出，**不做按路径的白名单**。
     *
     * 曾经有过一份 `SessionCookiePolicy`（只给 22 个 `v1.*` 路径带 cookie），但它与后端
     * 实际的登录闸门对不上：`v1.user/videoProgressList`、`v1.upload/withdraw`、
     * `v1.ad/session|progress|complete` 都要登录却不在表里，而 `/api.php/danmaku/send`
     * 根本不在这套路径命名空间（macCMS 自带接口，靠 cookie 认人，见插件 `Auth/Session.php`
     * 头部注释的教训）。按白名单分流会让这些请求静默变成游客请求——403 或空数据，
     * 编译器与后端都不会报"白名单漏了一个"。因此保留全量发送。
     */
    override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty()

    /** 整体清空：`ConcurrentHashMap.clear()` 自身并发安全，不用先遍历再逐个删。 */
    fun clear() = store.clear()

    /**
     * 从持久化会话回灌 cookie。
     *
     * jar 只在内存里，进程重启就空了；而 DataStore 里还留着 7 个会话 cookie。
     * 不回灌的话，冷启动后的第一个 `v1.user/detail` 就是无 cookie 请求，
     * 后端回「请先登录」——用户看到的就是"退出应用重进又变未登录"。
     */
    fun restore(host: String, cookies: Map<String, String>) {
        if (host.isBlank() || cookies.isEmpty()) return
        val now = System.currentTimeMillis()
        // 非 ASCII 值（老版本存过解码后的中文昵称）直接丢：OkHttp 会在发请求时抛
        // IllegalArgumentException 并崩在 OkHttp 线程，比缺一个 cookie 严重得多。
        val restored = cookies
            .filterValues { value -> value.all { it.code in COOKIE_ASCII_RANGE } }
            .mapNotNull { (name, value) -> cookieOf(host, name, value, now) }
        if (restored.isEmpty()) return
        store.compute(host) { _, existing -> merge(existing.orEmpty(), restored) }
    }

    private fun cookieOf(host: String, name: String, value: String, now: Long): Cookie? =
        runCatching {
            Cookie.Builder()
                .domain(host)
                .path("/")
                .name(name)
                .value(value)
                .expiresAt(now + RESTORED_TTL_MS)
                .build()
        }.getOrNull()

    private companion object {
        /** 回灌 cookie 的本地有效期：会话本身由后端 `user_check` 的 exp 校验。 */
        const val RESTORED_TTL_MS = 30L * 24 * 60 * 60 * 1000

        /** Cookie 值允许的可打印 ASCII 范围。 */
        val COOKIE_ASCII_RANGE = 0x20..0x7e
    }

    fun snapshot(): Map<String, List<Cookie>> = store.toMap()

    /**
     * 全部 cookie，同名取最后下发的那个（登录后重下发会覆盖旧值）。
     *
     * 遍历的是 [ConcurrentHashMap] 的值视图，迭代弱一致但不抛异常；
     * 每个元素都是不可变 List，所以不会读到写了一半的 cookie 集合。
     */
    fun all(): Map<String, String> = store.values.flatten().associate { cookie ->
        cookie.name to cookie.value
    }
}

/**
 * 网络入口。刻意用 class + 显式构造，不做全局单例（见 AGENTS.md 第 6 节）。
 */
class ApiClient(
    host: String,
    private val signature: ApiSignature,
    private val cookieJar: StarveilCookieJar = StarveilCookieJar(),
    enableLogging: Boolean = false,
) {
    /** 站点根地址（带 scheme，不含路径）。 */
    private val origin: String = host.trim().trimEnd('/')

    /** 后端主机名（不含 path），用于 cookie 归属。 */
    private val baseHost: String = host.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .trimEnd('/')
        .substringBefore('/')

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        // 签名参数集中注入：接口方法不再逐个声明 apikey/keytime
        .addInterceptor(ApiSignatureInterceptor(signature))
        // 用 TimeUnit 重载而非 java.time.Duration：后者要求 minSdk 26 或脱糖，
        // 本工程 minSdk 24 且不引入 desugaring。
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .apply {
            // 安全基线：不注册任何 TrustManager / HostnameVerifier 覆盖，
            // 也不允许明文降级。见 docs/security.md 第 1 节。
            if (enableLogging) {
                // 只开 BASIC：方法、URL、状态码、耗时，足够把线上 4xx/5xx 与客户端行为对上。
                // 绝不开 BODY——请求头里有 Cookie、响应体里有播放地址与会话串，进 logcat 等于泄密。
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                        redactHeader("Cookie")
                        redactHeader("Set-Cookie")
                    },
                )
            }
        }
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        // 路径前缀集中定义在 ApiPaths，禁止在别处再写一遍
        .baseUrl(host.trimEnd('/') + "/" + ApiPaths.PREFIX)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    /**
     * CMS 内置接口（`/api.php/...`）：弹幕库在 CMS 里，与 `v1.*` 不是同一个路径前缀，
     * 也不带签名参数，所以单独一个 Retrofit 实例，共用同一个 OkHttp（cookie 与 UA 一致）。
     */
    private val cmsRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(origin + "/api.php/")
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    /** CMS 弹幕：网页播放器与后台「弹幕管理」读的同一张表。 */
    val cmsDanmakuApi: CmsDanmakuApi = cmsRetrofit.create(CmsDanmakuApi::class.java)

    val api: StarveilApi = retrofit.create(StarveilApi::class.java)

    /** 分类接口，见 [VodTypeApi] 注释。 */
    val typeApi: VodTypeApi = retrofit.create(VodTypeApi::class.java)

    /** 搜索接口，见 [VodSearchApi] 注释。 */
    val searchApi: VodSearchApi = retrofit.create(VodSearchApi::class.java)

    /** 首页内容位接口，见 [HomeContentApi]。 */
    val homeApi: HomeContentApi = retrofit.create(HomeContentApi::class.java)

    /** 评论接口，见 [CommentApi]。 */
    val commentApi: CommentApi = retrofit.create(CommentApi::class.java)

    /** 继续观看接口，见 [ProgressListApi]。 */
    val progressApi: ProgressListApi = retrofit.create(ProgressListApi::class.java)

    /** 积分中心（签到/任务/分享），见 [PointsApi]。 */
    val pointsApi: PointsApi = retrofit.create(PointsApi::class.java)

    /** 账号资料写接口（改昵称 / 上传头像），见 [ProfileApi]。 */
    val profileApi: ProfileApi = retrofit.create(ProfileApi::class.java)

    /** 反馈留言，见 [GbookApi]。 */
    val gbookApi: GbookApi = retrofit.create(GbookApi::class.java)

    fun clearSession() = cookieJar.clear()

    /** 用持久化的会话 cookie 填充内存 jar（冷启动调用一次）。 */
    fun restoreSessionCookies(cookies: Map<String, String>) {
        cookieJar.restore(host = sessionHost(), cookies = cookies)
    }

    /** 会话 cookie 归属的 host：与 Retrofit baseUrl 同源。 */
    private fun sessionHost(): String = baseHost

    /** 当前会话 cookie（响应 Set-Cookie 累积结果），登录成功后由 [com.starveil.core.data.AuthRepository] 读取。 */
    fun sessionCookies(): Map<String, String> = cookieJar.all()

    /**
     * 线路解析：GET 解析站模板，把响应收敛成 [PlayStream]。
     *
     * 提取自旧 的 JSON 模式分支，并补上双流：
     * - 先按 JSON 解析（`{code,url,audio,type,...}`），B 站的 `audio` 字段就是音轨；
     * - JSON 解析不出来（解析站返回 HTML/JS）时才退回正则捞第一个播放地址。
     *
     * 只有 URL、没有 type 的老平台不会被误判：`ParseRules.normalizeStream` 仍看后缀。
     *
     * 阻塞调用，调用方必须在 IO 调度器上执行（:app 的 LineProbe 实现负责）。
     */
    fun probePlayUrl(template: String, vodUrl: String): PlayStream? {
        val requestUrl = signedParseUrl(template, vodUrl)
        val request = okhttp3.Request.Builder().url(requestUrl).build()
        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            parseJsonResult(body) ?: sniffFirstUrl(body)
        }
    }

    /**
     * 拉官方弹幕（解析站下发的 `danmu` 地址，指向 qilin-danmu）。
     *
     * 地址由解析结果给出，不在固定 host 上，所以不走 Retrofit，直接用同一个 OkHttp 实例。
     * 但"解析站给的"不等于可信：入场先过 [ParseHostPolicy]，只放行 https 且非内网的地址，
     * 否则被控的解析站能让设备去请求任意地址（含内网），而且用的是带签名拦截器的同一个 client。
     * 失败返回空列表：弹幕挂了不能影响播放（解析站自己也是这个口径）。
     */
    fun fetchOfficialDanmu(url: String): List<com.starveil.core.model.Danmu> {
        // 不合格直接回空、不抛：弹幕是可选功能，地址不可信时宁可没有弹幕
        if (!ParseHostPolicy.permits(url)) return emptyList()
        val request = okhttp3.Request.Builder().url(url).build()
        return runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string().orEmpty()
                json.decodeFromString<com.starveil.core.network.dto.OfficialDanmuListDto>(body)
                    .toDomain()
            }
        }.getOrDefault(emptyList())
    }

    /** JSON 响应 → 流；字段缺失或不是 JSON 时返回 null，交给正则兜底。 */
    private fun parseJsonResult(body: String): PlayStream? =
        runCatching { json.decodeFromString<ParseResultDto>(body) }
            .getOrNull()
            ?.let { ParseRules.normalizeStream(it.url, it.type, it.audio, it.danmu, it.referer) }

    /** 的正则兜底：从任意响应体里捞第一个像播放地址的 URL。 */
    private fun sniffFirstUrl(body: String): PlayStream? = URL_PATTERN.findAll(body)
        .map { it.value.trimEnd('"', '\'', ',', ')', ';') }
        .firstOrNull { ParseRules.looksLikePlayUrl(it) }
        ?.let { ParseRules.normalizeStream(it) }

    /**
     * 拼出带签名的解析请求地址。
     *
     * 供 :app 的受限 WebView 直接 loadUrl；签名仍在网络层生成，UI 层不碰密钥。
     */
    fun signedParseUrl(template: String, vodUrl: String): String {
        val sign = signature.current()
        return ParseRules.buildParseUrl(template, vodUrl, sign.apiKey, sign.keyTime)
    }

    private companion object {
        /** 从任意响应体里捞 URL 的粗匹配；是否算播放地址交给 ParseRules 判定。 */
        val URL_PATTERN = Regex("""https?://[^\s"'<>\\]+""")
    }
}
