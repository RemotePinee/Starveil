package com.starveil.core.model

/**
 * 解析线路规则。
 *
 * 判定逻辑收敛在本文件，不包含任何 WebView 配置——明文混合内容、文件访问、
 * 证书放行这类做法违反 docs/security.md 第 1 节，一律不做。
 *
 * 散落的魔法数字（探活 8 秒、状态码 "200"、嗅探后缀）集中成常量，
 * 后续改规则只动本文件。
 */
object ParseRules {

    /** 一条线路的探活超时。 */
    const val LINE_PROBE_SECONDS = 15

    /** 后端解析接口成功码，注意按**字符串**比较。 */
    const val SUCCESS_CODE = "200"

    /** JSON 模式的判定标记。 */
    private const val JSON_MARKER = "json"

    /** 模板里连续点号被压成单个点。 */
    private const val DOUBLE_DOT = ".."

    /**
     * 播放地址嗅探后缀。命中即视为拿到真实播放地址。
     * `/m3u8?` 单独列出是因为带鉴权参数的地址不以 .m3u8 结尾。
     */
    private val PLAY_URL_MARKERS = listOf(".mp4", ".m3u8", "/m3u8?", ".flv")

    /**
     * 服务端声明过的流类型。命中即认为是播放地址，不再要求后缀。
     *
     * B 站返回 `type = "dash"`，地址是 relay 的 `/stream?...`，没有任何后缀，
     * 只看 [PLAY_URL_MARKERS] 会把它判成"不是播放地址"而整条线路失败。
     */
    private val STREAM_TYPES = setOf("dash", "m3u8", "mp4", "flv", "hls")

    /**
     * 拼接解析线路的请求地址。
     *
     * ：`parseUrls[index] + url + "&apikey=" + sign + "&keytime=" + keyTime`。
     * 模板自带 `?`，所以这里用 `&` 续接。
     */
    fun buildParseUrl(
        template: String,
        vodUrl: String,
        apiKey: String,
        keyTime: String,
    ): String {
        val raw = "$template$vodUrl&apikey=$apiKey&keytime=$keyTime"
        return if (usesJsonEndpoint(raw)) raw.replaceFirst(DOUBLE_DOT, ".") else raw
    }

    /**
     * 判定走 JSON 接口还是 WebView 嗅探。
     *
     * 是 `contains("json") || contains("..")`，大小写敏感，照搬。
     * 解析站模板里写 `..json` 是常见笔误，所以两个条件必须同时保留。
     */
    fun usesJsonEndpoint(requestUrl: String): Boolean =
        requestUrl.contains(JSON_MARKER) || requestUrl.contains(DOUBLE_DOT)

    /**
     * 归一化解析结果。
     *
     * 只判断 `startsWith("http")`，`httpx://` 这类脏数据会被原样放行；
     * 要求真正的 scheme。协议相对地址（`//host/path`）补 `https:`。
     */
    fun normalizePlayUrl(raw: String?): String? {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) return null
        return when {
            url.startsWith("https://", ignoreCase = true) -> url
            url.startsWith("http://", ignoreCase = true) -> url
            // 协议相对地址（//host/path）
            url.startsWith("//") -> "https:$url"
            // 未知 scheme：按补 https: 前缀，而不是当成裸域名拼出双斜杠
            url.contains("://") -> "https:$url"
            // 裸域名或裸路径：只补 "https:"，会得到 https:host/path 这种残缺地址
            else -> "https://$url"
        }
    }

    /**
     * 把服务端的一次解析结果收敛成 [PlayStream]。
     *
     * 判定顺序：先看 `type`（服务端说是 dash/m3u8/mp4 就认），再用后缀兜底，
     * 这样老平台与 B 站双流走同一入口。
     *
     * [audioRaw] 归一化失败时只丢音轨、保留视频，不要让整条线路失败——
     * 用户至少还能看到画面（历史版本双流失败的表现就是"只有画面没声音"）。
     *
     * [refererRaw] 是解析站下发的防盗链 Referer，B 站/爱奇艺的 CDN 缺了它直接 403，
     * 所以必须原样带进 [PlayStream]，由状态机并进播放请求头。
     */
    fun normalizeStream(
        rawUrl: String?,
        type: String? = null,
        audioRaw: String? = null,
        danmuRaw: String? = null,
        refererRaw: String? = null,
    ): PlayStream? {
        val declared = type?.trim()?.lowercase().orEmpty()
        return normalizePlayUrl(rawUrl)
            ?.takeIf { declared in STREAM_TYPES || looksLikePlayUrl(it) }
            ?.let {
                PlayStream(
                    url = it,
                    audioUrl = normalizePlayUrl(audioRaw),
                    type = declared,
                    danmuUrl = danmuRaw?.trim()?.takeIf { url -> url.startsWith("http") },
                    referer = refererRaw?.trim()?.takeIf { referer -> referer.isNotEmpty() },
                )
            }
    }

    /** 嗅探命中判定。 */
    fun looksLikePlayUrl(url: String?): Boolean {
        val candidate = url?.trim().orEmpty()
        if (candidate.isEmpty()) return false
        return PLAY_URL_MARKERS.any { candidate.contains(it, ignoreCase = true) }
    }

    /**
     * 直链判定 / 请求头注入判定（、:1502-1507）。
     *
     * 是 `url.matches(it.toRegex())`，**全串匹配**，不是包含。
     * 正则由后端下发，写坏了历史版本会抛 PatternSyntaxException 崩溃；
     * 把非法正则当作"不匹配"，宁可多走一次解析也不要崩在起播路径上。
     */
    fun matchesAnyPattern(url: String, patterns: List<String>): Boolean {
        if (url.isEmpty()) return false
        return patterns.any { pattern ->
            pattern.isNotBlank() && runCatching { Regex(pattern).matches(url) }.getOrDefault(false)
        }
    }

    /** `features` / `parse2` 是逗号分隔。 */
    fun splitByComma(value: String?): List<String> = splitAndTrim(value, ",")

    /** `issethead` / `headers` 是双竖线分隔。 */
    fun splitByHeaderSeparator(value: String?): List<String> = splitAndTrim(value, "||")

    /**
     * 请求头注入判定。
     *
     * 只有播放地址命中 `issethead` 里的正则时才带上 headers；不命中返回空表，
     * 由播放器用默认 UA 直接取流。
     */
    fun resolveHeaders(
        url: String,
        patterns: List<String>,
        headers: Map<String, String>,
    ): Map<String, String> = if (matchesAnyPattern(url, patterns)) headers else emptyMap()

    private fun splitAndTrim(value: String?, separator: String): List<String> =
        value?.split(separator)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    /** 解析结果状态码判定。 */
    fun isSuccessCode(code: String?): Boolean = code?.trim() == SUCCESS_CODE

    /**
     * 下一个线路下标。越界返回 null，调用方据此报"全部线路失败"。
     */
    fun nextLineIndex(currentIndex: Int, lineCount: Int): Int? {
        if (lineCount <= 0) return null
        val next = currentIndex + 1
        return if (next in 0 until lineCount) next else null
    }

    /**
     * RC4 解密密钥：解析站域名 + 后端下发的 jiexi_key。
     *
     * 在 App 侧用 `MOGAI_BASE_URL.split("//")[1]` 取域名，
     * 显式拆开，避免把一个 URL 字符串当密钥用。
     */
    fun decryptionKey(baseUrl: String, jiexiKey: String): String {
        val host = baseUrl.substringAfter("://", baseUrl).trimEnd('/')
        return host + jiexiKey
    }

    /**
     * 地址是否命中后台配的「直链正则」。
     *
     * 命中就该跳过解析站直接播：m3u8 / mp4 这类地址拿去解析只会得到一次失败，
     * 用户看到的是"全部线路解析失败"，而片子其实一点问题都没有。
     *
     * 正则写错时当作不命中——一条烂配置不该让整集播不了。
     */
    fun isDirectLink(url: String, patterns: List<String>): Boolean {
        if (url.isBlank()) return false
        return patterns.any { pattern ->
            pattern.isNotBlank() &&
                runCatching { Regex(pattern).containsMatchIn(url) }.getOrDefault(false)
        }
    }
}
