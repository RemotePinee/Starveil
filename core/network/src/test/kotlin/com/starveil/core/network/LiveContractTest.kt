package com.starveil.core.network

import com.starveil.core.network.mapper.toDomain
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 直播接口契约（路径常量 ）。
 *
 * 只有列表：`v1.zhibo` 无参数，壳仍是 `PageResult`，但旧页面只用 `data.list`。
 * 后端 bean `LiveBean.id` 是 `long`，频道号可能超过 Int，夹具特意放了一个超界值。
 */
class LiveContractTest {

    private lateinit var server: MockWebServer
    private lateinit var api: StarveilApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = ApiClient(
            host = server.url("/").toString(),
            signature = ApiSignature(secret = "test-secret"),
        ).api
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `live list sends paging parameters`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_zhibo.json")))

        api.liveList(page = 3, limit = 60)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/icciu_api.php/v1.zhibo", pathOf(request))
        val url = requireNotNull(request.requestUrl)
        // 早期客户端不传分页，后端默认只回 20 条，后台导几百个频道也只显示 20 个
        assertEquals("3", url.queryParameter("page"))
        assertEquals("60", url.queryParameter("limit"))
    }

    @Test
    fun `live list defaults to first page of thirty`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_zhibo.json")))

        api.liveList()

        val url = requireNotNull(server.takeRequest().requestUrl)
        assertEquals("1", url.queryParameter("page"))
        assertEquals("30", url.queryParameter("limit"))
    }

    @Test
    fun `live fixture keeps long channel id and nullable url`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_zhibo.json")))

        val channels = requireNotNull(api.liveList().data).list.map { it.toDomain() }

        assertEquals(2, channels.size)
        assertEquals(12L, channels.first().id)
        assertEquals("CCTV-1 综合", channels.first().name)
        assertEquals("https://img.example.com/live/1.jpg", channels.first().cover)
        assertEquals("https://live.example.com/cctv1.m3u8", channels.first().url)
        // 频道号 4294967296 超过 Int 上限，id 必须是 Long；缺 url 的频道保持 null
        assertEquals(4_294_967_296L, channels[1].id)
        assertNull(channels[1].cover)
        assertNull(channels[1].url)
    }

    private fun pathOf(request: RecordedRequest): String =
        request.path.orEmpty().substringBefore('?')

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/$name")) { "缺少测试夹具：$name" }.readText()
}
