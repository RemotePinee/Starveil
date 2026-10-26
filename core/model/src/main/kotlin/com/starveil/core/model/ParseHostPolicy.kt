package com.starveil.core.model

/**
 * 解析页域名准入策略。
 *
 * 背景：解析页 URL 由后端下发，客户端不能假定它安全。
 * 把 WebView 配成"证书错误放行 + 允许 file 访问"，等于把这个 URL 当成可信输入，
 * 见 docs/security.md 第 5 节。
 *
 * 只做**客户端能做的**一层收敛：
 * - 只允许 https，明文一律拒绝（解析站若只有 http，宁可解析失败）
 * - 拒绝本机与内网地址，避免后端被篡改时把 WebView 指向内网服务（SSRF 面）
 *
 * 不解决的问题：DNS 重绑定、解析站自身被入侵。前者需要网络层固定 IP，后者无解。
 */
object ParseHostPolicy {

    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "0.0.0.0")

    /** 是否允许把该地址交给解析 WebView。 */
    fun permits(url: String?): Boolean {
        val raw = url?.trim().orEmpty()
        val host = if (raw.startsWith("https://", ignoreCase = true)) hostOf(raw).lowercase() else ""
        return host.isNotEmpty() && host !in LOOPBACK_HOSTS && !isPrivateAddress(host)
    }

    /**
     * 取 host（去掉端口与 IPv6 方括号）。不用 `java.net.URI`：
     * 后端下发的地址经常带未编码的查询串，URI 会直接抛异常。
     */
    private fun hostOf(url: String): String {
        val afterScheme = url.substringAfter("://", "")
        val authority = afterScheme.substringBefore('/').substringBefore('?')
        val withoutUserInfo = authority.substringAfterLast('@')
        return when {
            withoutUserInfo.startsWith("[") -> withoutUserInfo.substringBefore(']').removePrefix("[")
            else -> withoutUserInfo.substringBefore(':')
        }
    }

    private fun isPrivateAddress(host: String): Boolean {
        // mapNotNull 会吃掉解析失败的段，长度校验因此同时覆盖"非数字"和"段数不对"
        val numbers = host.split('.').mapNotNull { it.toIntOrNull() }
        val valid = numbers.size == 4 && numbers.all { it in 0..255 }
        val first = numbers.firstOrNull() ?: -1
        val second = numbers.getOrNull(1) ?: -1
        return valid && (first in PRIVATE_FIRST_OCTETS || (first to second) in PRIVATE_PAIRS)
    }

    /** 整个第一段都属于私网/保留：0/8、10/8、127/8。 */
    private val PRIVATE_FIRST_OCTETS = setOf(0, 10, 127)

    /** 需要看第二段的：192.168/16、169.254/16、172.16/12。 */
    private val PRIVATE_PAIRS = setOf(192 to 168, 169 to 254) +
        (16..31).map { 172 to it }
}
