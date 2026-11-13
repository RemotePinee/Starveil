package com.starveil.core.data.cast.relay

import java.net.URI

/**
 * m3u8 改写：把播放列表里的**分片地址与密钥地址**换成手机代理自己的地址。
 *
 * 为什么必须改写：电视拿到的是播放列表，它只会照着里面的 URI 去请求。
 * 中转投屏的意义就在于——这些请求都先到手机，由手机补 Referer/UA、走解密，
 * 再把字节吐给电视。所以 playlist 里任何电视会去访问的 URI 都要指回手机。
 *
 * 纯函数，输入输出都是字符串：这是整条中转链路里最该被单测覆盖的一段。
 */
internal object M3u8Rewriter {

    /**
     * @param playlist 上游返回的 m3u8 原文
     * @param baseUrl 播放列表自身的绝对地址（用于解析相对路径）
     * @param proxyBase 手机代理的基地址，例如 `http://192.168.1.191:18080`
     */
    fun rewrite(playlist: String, baseUrl: String, proxyBase: String): String =
        playlist.lineSequence().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith("#EXT-X-KEY") -> rewriteKey(line, baseUrl, proxyBase)
                line.startsWith("#") -> line
                else -> proxyUri(proxyBase, resolve(baseUrl, line.trim()))
            }
        }

    /** `#EXT-X-KEY:...URI="key.bin"...` → 把 URI 换成代理地址，保证电视拿到的是解密后的密钥。 */
    private fun rewriteKey(line: String, baseUrl: String, proxyBase: String): String {
        val match = KEY_URI.find(line) ?: return line
        val original = match.groupValues[1]
        val proxied = proxyUri(proxyBase, resolve(baseUrl, original))
        return line.replaceRange(match.range, """URI="$proxied"""")
    }

    /** 相对路径按播放列表所在目录解析；绝对地址原样返回。 */
    private fun resolve(baseUrl: String, line: String): String {
        val absolute = line.startsWith("http://", ignoreCase = true) ||
            line.startsWith("https://", ignoreCase = true)
        val base = runCatching { URI(baseUrl) }.getOrNull()
        val root = "${base?.scheme}://${base?.authority}"
        return when {
            absolute -> line
            base == null -> line
            line.startsWith("/") -> root + line
            else -> baseUrl.substringBeforeLast('/', "") + "/" + line
        }
    }
    /** 拼代理地址；原始地址以参数形式带上（编码后再拼，避免 `?`、`&` 打架）。 */
    private fun proxyUri(proxyBase: String, absolute: String): String =
        proxyBase + SEGMENT_PATH + "?u=" + encode(absolute)

    fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    fun decode(value: String): String = java.net.URLDecoder.decode(value, "UTF-8")

    private const val SEGMENT_PATH = "/cast/media"
    private val KEY_URI = Regex("""URI="([^"]+)"""")
}
