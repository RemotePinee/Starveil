package com.starveil.core.data.cast

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * 局域网控制通道：对电视的 HTTP 请求走**裸 Socket**，不走 OkHttp / HttpURLConnection。
 *
 * 为什么不用现成客户端：本应用的 `networkSecurityConfig` 全局禁止明文
 * （`app/src/main/res/xml/network_security_config.xml`），而 DLNA 控制地址必然是
 * `http://192.168.x.x:port/...`。`HttpURLConnection` 与 OkHttp 都会按该策略抛
 * `CleartextNotPermitted`。裸 Socket 不受这条策略约束，代价是解析要自己做：
 *
 * - **按 Content-Length 收尾**：不能一直读到 EOF。电视多数带 `Connection: keep-alive`，
 *   读到 EOF 会等到读超时，于是"电视明明回了 200，我们却判失败"。
 * - **分块传输**：`Transfer-Encoding: chunked` 要解码。
 * - **超长即失败**：超过上限判失败，而不是截断后当成功。
 * - **只发私有网段**：10/8、172.16/12、192.168/16、169.254/16、127/8。
 * - **保留非 2xx 正文**：SOAP 的 `UPnPError` 全在错误响应里，丢掉就只剩"没响应"。
 */
internal class LanHttp(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) {

    /** GET 一个局域网地址；只要 2xx 的正文，其它一律 null。 */
    fun get(url: String, maxBytes: Int = MAX_DESCRIPTION_BYTES): String? =
        getRaw(url, maxBytes)?.takeIf { it.code in 200..299 }?.body

    /** GET 的原始响应（含非 2xx）：诊断"描述文档抓不到"时需要状态码。 */
    fun getRaw(url: String, maxBytes: Int = MAX_DESCRIPTION_BYTES): LanResponse? =
        execute(method = "GET", url = url, body = null, maxBytes = maxBytes)

    /** POST 一条 SOAP 请求；返回原始响应，失败原因由调用方翻译。 */
    fun post(
        url: String,
        body: String,
        soapAction: String,
        maxBytes: Int = MAX_SOAP_BYTES,
    ): LanResponse? = execute(
        method = "POST",
        url = url,
        body = body,
        headers = soapHeaders(soapAction),
        maxBytes = maxBytes,
    )

    private fun execute(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int = MAX_SOAP_BYTES,
    ): LanResponse? {
        val target = parseLanUrl(url) ?: return null
        val payload = body?.toByteArray(Charsets.UTF_8)
        val attempt = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(target.host, target.port), connectTimeoutMs)
                socket.soTimeout = readTimeoutMs
                socket.getOutputStream().writeRequest(method, target, headers, payload)
                socket.getInputStream().readResponse(maxBytes)
            }
        }
        attempt.onFailure { failure ->
            // 仅调试包输出：区分"连不上 / 写失败 / 读超时"
            CastLog.debug {
                "$method ${CastLog.hostOf(url)} failed: ${failure.javaClass.simpleName}: ${failure.message}"
            }
        }
        return attempt.getOrNull()?.let(::parseResponse)
    }

    /** 解析并校验目标：只允许 http、只允许私有 IPv4、端口默认 80。 */
    private fun parseLanUrl(url: String): LanTarget? {
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.scheme?.lowercase() != "http") return null
        val host = uri.host
        return if (host != null && isPrivateLanHost(host)) {
            LanTarget(host, if (uri.port > 0) uri.port else 80, uri.toPathWithQuery())
        } else {
            null
        }
    }

    /** 路径 + 查询串；空路径按 `/` 处理。 */
    private fun URI.toPathWithQuery(): String = buildString {
        append(rawPath?.ifBlank { "/" } ?: "/")
        rawQuery?.let { append('?').append(it) }
    }

    private fun OutputStream.writeRequest(
        method: String,
        target: LanTarget,
        headers: Map<String, String>,
        payload: ByteArray?,
    ) {
        val head = buildString {
            append(method).append(' ').append(target.path).append(" HTTP/1.1\r\n")
            append("Host: ").append(target.host).append(':').append(target.port).append("\r\n")
            append("Connection: close\r\n")
            append("User-Agent: Starveil-Cast\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            if (payload != null) append("Content-Length: ").append(payload.size).append("\r\n")
            append("\r\n")
        }
        write(head.toByteArray(Charsets.US_ASCII))
        if (payload != null) write(payload)
        flush()
    }

    /**
     * 读一个完整响应：头部一到手就看 `Content-Length`，有长度只读到该长度；
     * 没有长度（或分块）才读到连接关闭。超限返回 null。
     */
    private fun InputStream.readResponse(maxBytes: Int): ByteArray? {
        val input = BufferedInputStream(this)
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK)
        var expected = -1
        var read = input.read(chunk)
        while (read > 0) {
            out.write(chunk, 0, read)
            if (expected < 0) expected = expectedTotalOf(out.toByteArray())
            if (expected > 0 && out.size() >= expected) break
            read = input.read(chunk)
        }
        val bytes = out.toByteArray()
        return if (bytes.size > maxBytes + MAX_HEADER_BYTES) null else trimToExpected(bytes, expected)
    }

    /** 头部 + Content-Length 的总字节数；没有长度（或分块）返回 -1。 */
    private fun expectedTotalOf(raw: ByteArray): Int {
        val headerEnd = indexOfHeaderEnd(raw)
        val headers = if (headerEnd >= 0) parseHeaders(String(raw, 0, headerEnd, Charsets.ISO_8859_1)) else emptyMap()
        val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
        val length = headers["content-length"]?.toIntOrNull()
        val usable = headerEnd >= 0 && !chunked
        return if (usable && length != null) headerEnd + HEADER_END.size + length else -1
    }

    /** 按 Content-Length 截到精确长度；没有长度（-1）就原样返回。 */
    private fun trimToExpected(raw: ByteArray, expected: Int): ByteArray =
        if (expected in 1..raw.size) raw.copyOf(expected) else raw

    private companion object {
        const val CONNECT_TIMEOUT_MS = 1_500
        const val READ_TIMEOUT_MS = 3_000
        const val MAX_DESCRIPTION_BYTES = 256 * 1024
        const val MAX_SOAP_BYTES = 64 * 1024
        const val MAX_HEADER_BYTES = 8 * 1024
        const val READ_CHUNK = 8 * 1024
    }
}

internal data class LanTarget(val host: String, val port: Int, val path: String)

/** HTTP 响应：状态码 + 正文。非 2xx 也保留，SOAP 的 UPnPError 就在里面。 */
internal data class LanResponse(val code: Int, val body: String)

/** 解析原始响应；不在这里过滤状态码，由调用方决定怎么处理。 */
internal fun parseResponse(raw: ByteArray): LanResponse? =
    headerBlock(raw)?.let { (head, bodyStart) ->
        val code = head.substringBefore("\r\n").split(' ').getOrNull(1)?.toIntOrNull() ?: return@let null
        decodeBody(parseHeaders(head), raw, bodyStart)?.let { LanResponse(code, it) }
    }

/** 头部文本 + 正文起点；没有完整头部返回 null。 */
private fun headerBlock(raw: ByteArray): Pair<String, Int>? {
    val end = indexOfHeaderEnd(raw)
    return if (end >= 0) String(raw, 0, end, Charsets.ISO_8859_1) to (end + HEADER_END.size) else null
}

/** 按响应头决定是否先解分块；解不出来返回 null。 */
private fun decodeBody(headers: Map<String, String>, raw: ByteArray, bodyStart: Int): String? {
    val bytes = if (bodyStart <= raw.size) raw.copyOfRange(bodyStart, raw.size) else ByteArray(0)
    val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
    return if (chunked) decodeChunked(bytes)?.toString(Charsets.UTF_8) else bytes.toString(Charsets.UTF_8)
}

private fun parseHeaders(head: String): Map<String, String> = head.lineSequence()
    .drop(1)
    .mapNotNull { line ->
        val colon = line.indexOf(':')
        if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
    }
    .toMap()

/** 分块编码解码；块长度非法或中途截断都返回 null。 */
internal fun decodeChunked(body: ByteArray): ByteArray? {
    val out = ByteArrayOutputStream()
    var index = 0
    var done = false
    var failed = false
    while (!done && !failed) {
        val lineEnd = indexOfCrlf(body, index)
        val size = chunkSize(body, index, lineEnd)
        val contentEnd = lineEnd + 2 + (size ?: 0)
        when {
            size == null -> failed = true
            size == 0 -> done = true
            contentEnd + 2 > body.size -> failed = true
            else -> {
                out.write(body, lineEnd + 2, size)
                index = contentEnd + 2
            }
        }
    }
    return if (done) out.toByteArray() else null
}

/** 读块长度行（`1a2b` 或 `1a2b;ext=1`）；行不完整或有非法字符返回 null。 */
private fun chunkSize(body: ByteArray, start: Int, lineEnd: Int): Int? =
    if (lineEnd < 0) {
        null
    } else {
        String(body, start, lineEnd - start, Charsets.ISO_8859_1)
            .substringBefore(';')
            .trim()
            .toIntOrNull(16)
    }

/** 头部结束位置：第一个 `\r\n\r\n` 的起点。 */
private fun indexOfHeaderEnd(raw: ByteArray): Int {
    for (i in 0..raw.size - HEADER_END.size) {
        if (isHeaderEndAt(raw, i)) return i
    }
    return -1
}

private fun isHeaderEndAt(raw: ByteArray, offset: Int): Boolean =
    HEADER_END.indices.all { raw[offset + it] == HEADER_END[it] }

private fun indexOfCrlf(raw: ByteArray, from: Int): Int {
    for (i in from..raw.size - 2) {
        if (raw[i] == '\r'.code.toByte() && raw[i + 1] == '\n'.code.toByte()) return i
    }
    return -1
}

/**
 * 只接受私有网段的 IPv4 字面量（含 127/8 回环，供本机联调与单测使用）。
 *
 * 不用 `InetAddress.isSiteLocalAddress`：它会做 DNS 解析，等于允许把任意域名带进来。
 */
internal fun isPrivateLanHost(host: String): Boolean {
    val octets = host.split('.').map { part -> part.toIntOrNull() ?: -1 }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    return isPrivateRange(octets[0], octets[1])
}

/** RFC1918 三段私网 + 链路本地 + 回环。 */
private fun isPrivateRange(a: Int, b: Int): Boolean =
    a == 10 ||
        a == 127 ||
        (a == 172 && b in 16..31) ||
        (a == 192 && b == 168) ||
        (a == 169 && b == 254)

private val HEADER_END = "\r\n\r\n".toByteArray()

private fun soapHeaders(soapAction: String): Map<String, String> = mapOf(
    "Content-Type" to "text/xml; charset=\"utf-8\"",
    "SOAPACTION" to "\"$soapAction\"",
)
