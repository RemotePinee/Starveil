package com.starveil.core.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 解析站响应的收敛规则。
 *
 * 重点锁 B 站的双流形态：`type = "dash"` + `audio` 字段，地址是 relay 的
 * `/stream?...`（没有 `.mp4` / `.m3u8` 后缀）。这条路径错了的表现是"有画面没声音"，
 * 或者整条线路被判失败，所以每种分支都要用例兜住。
 */
class ParseProbeTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = ApiClient(
            host = server.url("/").toString(),
            signature = ApiSignature(secret = "test-secret"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `bilibili dash response keeps the audio track`() {
        enqueue(
            """
            {"code":200,"platform":"bilibili","type":"dash","quality":"1080P+",
             "width":1920,"height":1080,"duration":845,
             "url":"https://relay.example.com/stream?p=bilibili&url=video",
             "audio":"https://relay.example.com/stream?p=bilibili&url=audio",
             "referer":"https://www.bilibili.com/"}
            """,
        )

        val stream = probe()

        assertEquals("https://relay.example.com/stream?p=bilibili&url=video", stream?.url)
        assertEquals("https://relay.example.com/stream?p=bilibili&url=audio", stream?.audioUrl)
        assertEquals("dash", stream?.type)
        assertTrue(stream?.isDualStream == true)
        // 防盗链 Referer 必须一路带到播放头，B 站 CDN 缺它直接 403
        assertEquals("https://www.bilibili.com/", stream?.referer)
    }

    @Test
    fun `other platforms return a single stream without audio`() {
        enqueue("""{"code":200,"type":"m3u8","url":"https://cdn.example.com/show.m3u8"}""")

        val stream = probe()

        assertEquals("https://cdn.example.com/show.m3u8", stream?.url)
        assertNull(stream?.audioUrl)
    }

    @Test
    fun `non json responses still fall back to url sniffing`() {
        enqueue("""<script>var src = "https://cdn.example.com/movie.mp4";</script>""")

        assertEquals("https://cdn.example.com/movie.mp4", probe()?.url)
    }

    @Test
    fun `json without a playable url is treated as a failed line`() {
        enqueue("""{"code":404,"msg":"解析失败"}""")

        assertNull(probe())
    }

    private fun enqueue(body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(body),
        )
    }

    private fun probe() = client.probePlayUrl(
        template = server.url("/json?url=").toString(),
        vodUrl = "https://www.bilibili.com/video/BV1xx",
    )
}
