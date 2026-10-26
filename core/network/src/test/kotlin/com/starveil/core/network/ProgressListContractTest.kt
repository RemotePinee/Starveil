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
 * 继续观看列表契约（`GET v1.user/videoProgressList`）。
 *
 * 三个不能漂的点：路径大小写、无查询参数、字段名与 `cuican_vlog` join `mac_vod` 的列一致。
 * 服务端已跳过下架影片，客户端不做二次过滤。
 */
class ProgressListContractTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ProgressListApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = ApiClient(
            host = server.url("/").toString(),
            signature = ApiSignature(secret = "test-secret"),
        ).progressApi
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `progress list hits the user route without a limit parameter`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_user_videoProgressList.json")))

        api.recent()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/icciu_api.php/v1.user/videoProgressList", pathOf(request))
        val url = requireNotNull(request.requestUrl)
        // 签名拦截器会补 apikey/keytime，所以不能断言 querySize=0；只断言不再传 limit
        assertNull(url.queryParameter("limit"))
    }

    @Test
    fun `fixture maps vod name poster indices and seconds`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_user_videoProgressList.json")))

        val items = requireNotNull(api.recent().data).list.map { it.toDomain() }

        assertEquals(2, items.size)
        val first = items.first()
        assertEquals(101, first.vodId)
        assertEquals("示例影片 A", first.vodName)
        assertEquals("https://img.example.com/a.jpg", first.vodPic)
        assertEquals(0, first.sourceIndex)
        // episode_index 是 0 起下标，集号 1 起由领域模型换算
        assertEquals(2, first.episodeIndex)
        assertEquals(3, first.episodeNumber)
        assertEquals(600, first.progressSec)
        assertEquals(1760000000L, first.updatedAt)

        // 第二条缺 vod_pic 且带未知字段：必须仍能解析，封面归一成 null
        val second = items[1]
        assertEquals(102, second.vodId)
        assertNull(second.vodPic)
        assertEquals(1, second.sourceIndex)
    }

    private fun pathOf(request: RecordedRequest): String =
        request.path.orEmpty().substringBefore('?')

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/$name")) { "缺少测试夹具：$name" }.readText()
}
