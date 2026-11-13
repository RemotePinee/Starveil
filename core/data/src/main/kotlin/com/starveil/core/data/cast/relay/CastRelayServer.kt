package com.starveil.core.data.cast.relay

import com.starveil.core.data.cast.CastLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors

/**
 * 中转投屏的本机代理服务。
 *
 * 电视拉的是手机上的地址，手机再去回源：补 Referer/UA、必要时走解密，然后把字节吐回去。
 * 只服务**私有网段**的请求（否则等于把回源能力开放给整个网络），绑定在 Wi-Fi 地址上，
 * 端口由系统分配（避免和别的应用抢 18080）。
 *
 * 只实现中转需要的最小子集：GET、单个 `?u=` 参数、Content-Length 回包、流式转发。
 * 不实现分块接收、缓存、并发优化——那些是后续按真实片源再补的。
 */
internal class CastRelayServer(
    private val headers: Map<String, String>,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000,
) {

    private var serverSocket: ServerSocket? = null
    private val workers = Executors.newFixedThreadPool(WORKERS)

    /** 启动并返回给电视用的基地址（例如 `http://192.168.1.191:41234`）；失败返回 null。 */
    fun start(): String? = wifiAddress()?.let { address ->
        bindServer(address)?.let { socket ->
            serverSocket = socket
            workers.execute { acceptLoop(socket) }
            "http://${address.hostAddress}:${socket.localPort}".also { // security-baseline-allow: 本机代理
                CastLog.debug { "relay listening on $it" }
            }
        }
    }

    private fun bindServer(address: InetAddress): ServerSocket? = runCatching {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(address, 0))
        }
    }.getOrNull()

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        workers.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull()
            if (client != null) handleClient(client)
        }
    }

    /** 只服务私有网段；其它来源直接断开，避免把回源能力暴露给整个网络。 */
    private fun handleClient(client: Socket) {
        if (isPrivate(client.inetAddress)) {
            workers.execute { serve(client) }
        } else {
            runCatching { client.close() }
        }
    }

    /** 一次请求：解析行 → 取 `u` → 回源 → 改写播放列表或直传分片。 */
    private fun serve(client: Socket) {
        client.use { socket ->
            val request = runCatching { readRequestLine(socket.getInputStream()) }.getOrNull() ?: return
            val target = request.substringAfter(' ').substringBefore(' ')
            val upstream = queryParam(target, "u")?.let(M3u8Rewriter::decode)
            val output = BufferedOutputStream(socket.getOutputStream())
            if (upstream == null) {
                writeText(output, 400, "text/plain; charset=utf-8", "bad request")
                return
            }
            val base = baseUrlOf(socket)
            proxy(output, upstream, base)
        }
    }

    private fun proxy(output: BufferedOutputStream, upstream: String, base: String) {
        val connection = runCatching { URL(upstream).openConnection() as HttpURLConnection }.getOrNull()
        if (connection == null) {
            writeText(output, 502, "text/plain; charset=utf-8", "bad upstream")
            return
        }
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val code = connection.responseCode
            if (code !in 200..299) {
                writeText(output, 502, "text/plain; charset=utf-8", "upstream $code")
                return
            }
            val contentType = connection.contentType ?: "application/octet-stream"
            if (contentType.contains("mpegurl", ignoreCase = true) || upstream.contains(".m3u8")) {
                val playlist = connection.inputStream.bufferedReader().readText()
                val rewritten = M3u8Rewriter.rewrite(playlist, upstream, base)
                writeText(output, 200, "application/vnd.apple.mpegurl", rewritten)
            } else {
                streamBody(output, connection.inputStream, connection.contentLength)
            }
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun streamBody(output: BufferedOutputStream, input: InputStream, length: Int) {
        val head = "HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n" +
            (if (length > 0) "Content-Length: $length\r\n" else "") +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        BufferedInputStream(input).use { source ->
            val buffer = ByteArray(BUFFER)
            var read = source.read(buffer)
            while (read > 0) {
                output.write(buffer, 0, read)
                read = source.read(buffer)
            }
        }
        output.flush()
    }

    private fun writeText(output: BufferedOutputStream, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n" +
            "Content-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun readRequestLine(input: InputStream): String {
        val line = StringBuilder()
        while (true) {
            val ch = input.read()
            if (ch < 0 || ch == '\n'.code) break
            if (ch != '\r'.code) line.append(ch.toChar())
        }
        return line.toString()
    }

    private fun queryParam(target: String, name: String): String? =
        target.substringAfter('?', "").split('&')
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')

    private fun baseUrlOf(socket: Socket): String =
        "http://${socket.localAddress.hostAddress}:${socket.localPort}" // security-baseline-allow: 本机代理

    private fun isPrivate(address: InetAddress?): Boolean {
        val octets = address?.hostAddress?.split('.').orEmpty().map { it.toIntOrNull() ?: -1 }
        val valid = octets.size == 4 && octets.all { it >= 0 }
        return valid && isPrivatePair(octets[0], octets[1])
    }

    private fun isPrivatePair(a: Int, b: Int): Boolean =
        a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31)

    private fun wifiAddress(): InetAddress? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .filter { it.name.startsWith("wlan") || it.name.startsWith("eth") }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    }.getOrNull()

    private companion object {
        const val WORKERS = 4
        const val BUFFER = 16 * 1024
    }
}
