package com.starveil.core.network

import com.starveil.core.model.TrySeeAccess
import com.starveil.core.model.TrySeeRules
import com.starveil.core.network.mapper.toDomain
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 试看接口契约（路径常量 ，
 * 调用点 ）。
 *
 * 三个 query 全是字符串：`id` = 影片 id，`nid` = 单集 nid，`mid` 恒为模块 id `"1"`。
 * `data` 里还有一层自己的 code/msg，不要拿内层 code 当成功判定。
 */
class TrySeeContractTest {

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
    fun `try see sends vod episode and default module id`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_user_checkVodTrySee.json")))

        api.checkVodTrySee(vodId = "101", episodeNumber = "3")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/icciu_api.php/v1.user/checkVodTrySee", pathOf(request))
        val url = requireNotNull(request.requestUrl)
        assertEquals("101", url.queryParameter("id"))
        assertEquals("3", url.queryParameter("nid"))
        // mid 是模块 id，恒传 1，不是播放源下标
        assertEquals("1", url.queryParameter("mid"))
    }

    @Test
    fun `try see fixture prefers remaining free count over vip status`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_user_checkVodTrySee.json")))

        val gate = requireNotNull(api.checkVodTrySee("101", "3").data).toDomain()

        // status=1 本来要 VIP，但 user_video=5 还有免费次数，按 TrySeeRules 放行
        assertEquals(TrySeeAccess.FREE, gate.access)
        assertEquals(6, gate.trySeeMinutes)
    }

    @Test
    fun `blocked fixture keeps the 888 sentinel`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_user_checkVodTrySee_blocked.json")))

        val gate = requireNotNull(api.checkVodTrySee("101", "9").data).toDomain()

        assertEquals(TrySeeAccess.NEED_PAY, gate.access)
        assertEquals(TrySeeRules.BLOCK_IMMEDIATELY_MINUTES, gate.trySeeMinutes)
    }

    @Test
    fun `unlock capability bits drive the three exits`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_user_checkVodTrySee_unlock.json")))

        val gate = requireNotNull(api.checkVodTrySee("101", "3").data).toDomain()

        assertEquals(TrySeeAccess.NEED_VIP, gate.access)
        assertTrue(gate.canUpgrade)
        assertTrue(gate.canRedeemPoints)
        assertFalse(gate.canWatchAd)
        assertEquals(30, gate.pointsPrice)
    }

    @Test
    fun `old backend without capability bits keeps one exit`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_user_checkVodTrySee_blocked.json")))

        val gate = requireNotNull(api.checkVodTrySee("101", "9").data).toDomain()

        // 老后端没有 can_vip / can_points：按 access 回退成单出口
        assertFalse(gate.canUpgrade)
        assertTrue(gate.canRedeemPoints)
        assertFalse(gate.canWatchAd)
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
