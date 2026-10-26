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
 * 专题接口契约。
 * 路径常量见 `:26`。
 *
 * 列表与详情是两条路径、两种壳：列表是分页壳（`PageResult`），详情是单个对象里
 * 直接内嵌 `vod_list`。后端 bean 的 `topic_id` 是 String，是页面原样塞进 Intent 的值。
 */
class TopicContractTest {

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
    fun `topic list sends no paging parameters`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_topic_topicList.json")))

        api.topicList()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/icciu_api.php/v1.topic/topicList", pathOf(request))
        val url = requireNotNull(request.requestUrl)
        // 旧 getTopicList() 无参；apikey/keytime 是拦截器统一注入的，不算业务参数
        assertNull(url.queryParameter("page"))
        assertNull(url.queryParameter("limit"))
    }

    @Test
    fun `topic list fixture maps string ids and tolerates missing fields`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_topic_topicList.json")))

        val page = requireNotNull(api.topicList().data)
        val topics = page.list.map { it.toDomain() }

        assertEquals(10, page.limit)
        assertEquals(2, topics.size)
        assertEquals("3", topics.first().id)
        assertEquals("港片精选", topics.first().name)
        assertEquals("<p>每周更新</p>", topics.first().content)
        // 第二条缺 topic_id / topic_pic，带未知字段：必须落成默认值而不是解析失败
        assertEquals("", topics[1].id)
        assertNull(topics[1].poster)
    }

    @Test
    fun `topic detail sends topic id`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_topic_topicDetail.json")))

        api.topicDetail(topicId = "3")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/icciu_api.php/v1.topic/topicDetail", pathOf(request))
        assertEquals("3", requireNotNull(request.requestUrl).queryParameter("topic_id"))
    }

    @Test
    fun `topic detail fixture keeps tag and embedded vods`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_topic_topicDetail.json")))

        val detail = requireNotNull(api.topicDetail("3").data).toDomain()

        assertEquals("港片精选", detail.name)
        assertEquals("港片", detail.tag)
        assertEquals(2, detail.vods.size)
        assertEquals(101, detail.vods.first().id)
        assertEquals("示例影片 A", detail.vods.first().name)
        // 第二条只有 id 和名字，播放列表缺省为空，不能抛解析异常
        assertTrue(detail.vods[1].playSources.isEmpty())
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
