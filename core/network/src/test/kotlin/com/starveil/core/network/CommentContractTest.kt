package com.starveil.core.network

import com.starveil.core.network.dto.ApiEnvelopeDto
import com.starveil.core.network.dto.CommentListDto
import com.starveil.core.network.mapper.toDomain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

/**
 * 评论接口契约（服务端 `CommentController.php`）。
 *
 * 三条不能漂的约定：
 * 1. 列表是 GET + query `vod_id` / `page` / `limit`，`total` 是全集条数；
 * 2. 发布是表单 POST，`data` 为 null，成功只看响应壳的 code；
 * 3. 未登录时 HTTP 401 且 body 仍是壳（`code=401`），客户端要能读出业务码。
 */
class CommentContractTest {
    /** 每个用例各自 new 一个 Json 会被编译器判为冗余创建，提到类级共用。 */
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var server: MockWebServer
    private lateinit var api: CommentApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = ApiClient(
            host = server.url("/").toString(),
            signature = ApiSignature(secret = "test-secret"),
        ).commentApi
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `list sends vod id page and limit then maps the page`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_comment_list.json")))

        val page = requireNotNull(api.list(vodId = 7, page = 1, limit = 20).data).toDomain()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/icciu_api.php/v1.comment", pathOf(request))
        val url = requireNotNull(request.requestUrl)
        assertEquals("7", url.queryParameter("vod_id"))
        assertEquals("1", url.queryParameter("page"))
        assertEquals("20", url.queryParameter("limit"))

        // total 是全集条数，不是当前页条数
        assertEquals(42, page.total)
        assertEquals(1, page.page)
        assertEquals(3, page.items.size)
        val first = page.items.first()
        assertEquals(901, first.id)
        assertEquals("第三集反转太狠了", first.content)
        assertEquals("夜航船", first.nickName)
        assertEquals("https://img.example.com/avatar/72.png", first.portrait)
        // created_at 是秒级时间戳，不做毫秒换算
        assertEquals(1_729_300_000L, first.createdAt)
    }

    @Test
    fun `fixture tolerates missing fields and unknown fields`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_comment_list.json")))

        val page = requireNotNull(api.list(vodId = 7, page = 1, limit = 20).data).toDomain()
        val third = page.items[2]

        // 缺 user_nick_name / user_portrait：昵称落成「游客」，头像空串
        assertEquals("游客", third.nickName)
        assertEquals("", third.portrait)
        // 第二条多出 status 字段，ignoreUnknownKeys 不能因此失败
        assertEquals(900, page.items[1].id)
    }

    @Test
    fun `post sends the comment as a form body`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_comment_post.json")))

        val envelope = api.post(vodId = "7", content = "第三集好看")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/icciu_api.php/v1.comment", pathOf(request))
        val body = request.body.readUtf8()
        assertTrue(body.contains("vod_id=7"))
        // 中文按 UTF-8 percent-encoding，与后端 `input->str('content')` 解出的一致
        assertTrue(body.contains("content=%E7%AC%AC%E4%B8%89%E9%9B%86%E5%A5%BD%E7%9C%8B"))

        // data 为 null 时 Unit 泛型不报错，成功只看 code
        assertEquals(200, envelope.code)
        assertEquals("评论已发布", envelope.msg)
    }

    @Test
    fun `unauthorized post surfaces 401 and keeps the business envelope`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(fixture("v1_comment_unauthorized.json")),
        )

        val error = runCatching { api.post(vodId = "7", content = "第三集好看") }.exceptionOrNull()

        assertTrue(error is HttpException)
        assertEquals(401, (error as HttpException).code())
        val body = requireNotNull(error.response()?.errorBody()).string()
        val envelope = json
            .decodeFromString<ApiEnvelopeDto<CommentListDto>>(body)
        assertEquals(401, envelope.code)
        assertEquals("请先登录", envelope.msg)
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
