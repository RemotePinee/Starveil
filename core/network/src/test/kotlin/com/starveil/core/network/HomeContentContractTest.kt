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
 * 首页内容位契约：编辑精选 / 热度榜 / 猜你喜欢（同一张 `{title,subtitle,vods}` 结构）
 * 与每日推荐条数。
 *
 * 这条接口取代"从列表裁掉前几条"的卡片位做法，所以解析层要守住两点：
 * 单对象壳（不是 list 壳），以及后台未配置时缺省 `vods` 落成空列表而不是解析失败——
 * UI 靠"空列表"判断该回退到列表切片。
 */
class HomeContentContractTest {

    private lateinit var server: MockWebServer
    private lateinit var api: HomeContentApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = ApiClient(
            host = server.url("/").toString(),
            signature = ApiSignature(secret = "test-secret"),
        ).homeApi
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `featured sends no query parameters`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_main_featured.json")))

        api.featured()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/icciu_api.php/v1.main/featured", pathOf(request))
        // 内容按站点配置下发，没有分页语义；apikey/keytime 由拦截器统一注入，不算业务参数
        assertNull(requireNotNull(request.requestUrl).queryParameter("page"))
        assertNull(requireNotNull(request.requestUrl).queryParameter("limit"))
    }

    @Test
    fun `featured fixture maps title and vods`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_main_featured.json")))

        val featured = requireNotNull(api.featured().data).toDomain()

        assertEquals("本周精选", featured.title)
        assertEquals("编辑部手挑", featured.subtitle)
        assertEquals(2, featured.items.size)
        assertEquals(101, featured.items.first().id)
        assertEquals("示例影片 A", featured.items.first().name)
        assertEquals("更新至12集", featured.items.first().remarks)
        // 第二条只有 id 与名字：缺字段必须降级，不能让整块精选解析失败
        assertEquals("示例影片 B", featured.items[1].name)
        assertTrue(featured.items[1].playSources.isEmpty())
    }

    @Test
    fun `missing vods become an empty list`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_main_featured_unset.json")))

        val featured = requireNotNull(api.featured().data).toDomain()

        assertEquals("编辑精选", featured.title)
        assertTrue(featured.items.isEmpty())
    }

    @Test
    fun `hot sends no query parameters`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_main_hot.json")))

        api.hot()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/icciu_api.php/v1.main/hot", pathOf(request))
        assertNull(requireNotNull(request.requestUrl).queryParameter("page"))
    }

    @Test
    fun `hot fixture maps the title and items`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_main_hot.json")))

        val hot = requireNotNull(api.hot().data).toDomain()

        assertEquals("热度榜", hot.title)
        assertEquals(2, hot.items.size)
        assertEquals(201, hot.items.first().id)
        assertEquals("热播影片 A", hot.items.first().name)
    }

    @Test
    fun `guess keeps a blank title for the UI to label`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_main_guess.json")))

        val guess = requireNotNull(api.guess().data).toDomain()

        // 后台没给标题时映射层不替它选文案：三个位置的回退标题各不相同
        assertEquals("", guess.title)
        assertEquals(listOf(301), guess.items.map { it.id })
    }

    @Test
    fun `push fixture maps the count`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_main_push.json")))

        val push = requireNotNull(api.push().data)

        assertEquals("/icciu_api.php/v1.main/push", pathOf(server.takeRequest()))
        assertEquals(3, push.count)
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
