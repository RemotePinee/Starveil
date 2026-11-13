package com.starveil.core.data.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/** 局域网控制通道：网段白名单、响应解析（含分块）、超限失败。 */
class LanHttpTest {

    @Test
    fun `private ipv4 ranges are allowed`() {
        listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.10", "169.254.1.1", "127.0.0.1")
            .forEach { assertTrue(it, isPrivateLanHost(it)) }
    }

    @Test
    fun `public addresses and hostnames are rejected`() {
        listOf("8.8.8.8", "172.32.0.1", "192.169.0.1", "example.com", "::1", "")
            .forEach { assertFalse(it, isPrivateLanHost(it)) }
    }

    @Test
    fun `response keeps status code so soap faults stay readable`() {
        val ok = parseResponse(response(200, "ok").toByteArray())
        assertEquals(200, ok?.code)
        assertEquals("ok", ok?.body)
        // 非 2xx 不再吞掉：电视的 UPnPError 就在这份正文里
        val fault = parseResponse(response(500, "<errorCode>716</errorCode>").toByteArray())
        assertEquals(500, fault?.code)
        assertEquals("<errorCode>716</errorCode>", fault?.body)
        assertNull(parseResponse("garbage".toByteArray()))
    }

    @Test
    fun `chunked body is decoded`() {
        val raw = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"

        assertEquals("hello world", parseResponse(raw.toByteArray())?.body)
    }

    @Test
    fun `malformed chunk size fails instead of returning partial body`() {
        val raw = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\nhello\r\n"
        assertNull(parseResponse(raw.toByteArray()))
    }

    @Test
    fun `http client reads a real response from local server`() {
        withServer(response(200, "<root>ok</root>")) { port ->
            assertEquals("<root>ok</root>", LanHttp().get("http://127.0.0.1:$port/desc.xml"))
        }
    }

    @Test
    fun `http client decodes chunked response from local server`() {
        val raw = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n"
        withServer(raw) { port ->
            assertEquals("hello", LanHttp().get("http://127.0.0.1:$port/desc.xml"))
        }
    }

    @Test
    fun `oversized body fails instead of truncating`() {
        val big = response(200, "x".repeat(16 * 1024))
        withServer(big) { port ->
            assertNull(LanHttp().get("http://127.0.0.1:$port/desc.xml", maxBytes = 1024))
        }
    }

    @Test
    fun `public host is refused before connecting`() {
        assertNull(LanHttp().get("http://8.8.8.8/desc.xml"))
    }

    private fun response(code: Int, body: String): String =
        "HTTP/1.1 $code OK\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"

    /** 起一个只回一次响应的本地 HTTP 服务，用来验真实的收发而不是只验纯函数。 */
    private fun withServer(rawResponse: String, block: (Int) -> Unit) {
        ServerSocket(0).use { server ->
            val worker = thread(isDaemon = true) {
                runCatching {
                    server.accept().use { client ->
                        client.getInputStream().read(ByteArray(8 * 1024))
                        client.getOutputStream().apply {
                            write(rawResponse.toByteArray())
                            flush()
                        }
                    }
                }
            }
            block(server.localPort)
            worker.join(1_000)
        }
    }
}
