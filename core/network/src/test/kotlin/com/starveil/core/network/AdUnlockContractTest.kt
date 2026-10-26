package com.starveil.core.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 直投激励视频三个接口的契约：`v1.ad/session` / `v1.ad/progress` / `v1.ad/complete`。
 *
 * 字段名与单位都固定：`vod_id` / `episode_number` 是表单字符串，`position` 是**秒**（当前播放位置，
 * 不是增量），`complete` 只带 `token`。`apikey` / `keytime` 由拦截器加在 query 上，
 * 不能混进表单，所以这里逐个断言表单内容而不只是断言请求发得出去。
 */
class AdUnlockContractTest {

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
    fun `session posts vod and episode and reads token seconds video`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_ad_session.json")))

        val data = requireNotNull(api.adSession(vodId = "101", episodeNumber = "3").data)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/icciu_api.php/v1.ad/session", pathOf(request))
        val form = formOf(request)
        assertEquals("101", form["vod_id"])
        assertEquals("3", form["episode_number"])
        assertEquals("tok-123", data.token)
        assertEquals(15, data.seconds)
        assertEquals("https://cdn.example.com/reward.mp4", data.videoUrl)
    }

    @Test
    fun `progress posts token and current position in seconds`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_ad_progress.json")))

        val data = requireNotNull(api.adProgress(token = "tok-123", positionSec = 10).data)

        val request = server.takeRequest()
        assertEquals("/icciu_api.php/v1.ad/progress", pathOf(request))
        val form = formOf(request)
        assertEquals("tok-123", form["token"])
        // 位置是秒：传 10 就必须原样发 10，不能乘 1000 变成毫秒
        assertEquals("10", form["position"])
        assertEquals(1, data.ok)
    }

    @Test
    fun `complete only carries the token and reports unlocked`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_ad_complete.json")))

        val data = requireNotNull(api.adComplete(token = "tok-123").data)

        val request = server.takeRequest()
        assertEquals("/icciu_api.php/v1.ad/complete", pathOf(request))
        val form = formOf(request)
        assertEquals("tok-123", form["token"])
        // 只带 token：多带 vod/集号会让服务端按另一个口径校验
        assertEquals(1, form.size)
        assertEquals(1, data.unlocked)
    }

    private fun pathOf(request: RecordedRequest): String =
        request.path.orEmpty().substringBefore('?')

    private fun formOf(request: RecordedRequest): Map<String, String> =
        request.body.readUtf8()
            .split('&')
            .filter { it.isNotBlank() }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/$name")) { "缺少测试夹具：$name" }.readText()
}
