package com.starveil.core.network

import com.starveil.core.network.mapper.toDomain
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 收藏接口契约。
 *
 * 三个接口分布在同一个路径 `v1.user/ulog` 上，靠 HTTP 方法区分（POST 收藏、
 * DELETE 取消），所以方法和路径必须成对断言；列表走 `v1.user/favs`。
 */
class FavoriteContractTest {

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
    fun `favorite list sends paging and ulog type`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_user_favs.json")))

        api.favoriteList(page = 1, limit = 10)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/icciu_api.php/v1.user/favs", pathOf(request))
        val url = requireNotNull(request.requestUrl)
        assertEquals("1", url.queryParameter("page"))
        assertEquals("10", url.queryParameter("limit"))
        assertEquals("2", url.queryParameter("ulog_type"))
    }

    @Test
    fun `favorite fixture maps ids title and poster`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_user_favs.json")))

        val items = requireNotNull(api.favoriteList(page = 1, limit = 10).data)
            .list
            .map { it.toDomain() }

        assertEquals(2, items.size)
        val first = items.first()
        assertEquals(27, first.ulogId)
        assertEquals(101, first.vodId)
        assertEquals("示例影片 A", first.title)
        // 旧 getPic() 优先 pic_thumb，为空才退回 pic
        assertEquals("https://img.example.com/a_thumb.jpg", first.poster)
        assertEquals("综艺", first.categoryName)
        assertEquals(1560877169L, first.createdAt)

        // 第二条缺 data（影片被删）且带未知字段，必须仍能解析
        val second = items[1]
        assertEquals(102, second.vodId)
        assertEquals("", second.title)
        assertNull(second.poster)
    }

    @Test
    fun `favorite add posts module id resource id and type`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_auth_success.json")))

        api.favoriteAdd(vodId = "101")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/icciu_api.php/v1.user/ulog", pathOf(request))
        val body = request.body.readUtf8()
        assertTrue(body.contains("id=101"))
        assertTrue(body.contains("mid=1"))
        assertTrue(body.contains("type=2"))
    }

    @Test
    fun `favorite remove deletes by comma joined ids`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_auth_success.json")))

        api.favoriteRemove(ids = "101,102")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/icciu_api.php/v1.user/ulog", pathOf(request))
        val url = requireNotNull(request.requestUrl)
        // ids 是影片 id 逗号串，不是 ulog_id，逗号不能被编码成 %2C 之外的东西
        assertEquals("101,102", url.queryParameter("ids"))
        assertEquals("2", url.queryParameter("type"))
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
