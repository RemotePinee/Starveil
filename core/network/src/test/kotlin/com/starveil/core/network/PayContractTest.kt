package com.starveil.core.network

import com.starveil.core.model.PayPeriod
import com.starveil.core.model.CardType
import com.starveil.core.network.mapper.toDomain
import com.starveil.core.network.mapper.toPlans
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

/**
 * 支付类接口契约。
 *
 * 五个接口全部靠路径 + 表单名区分语义：`v1.user/order` 同时是"下单"（POST `price`）
 * 与"查订单"（GET `order_code`）；`v1.user/buypopedom` 的六个字段少一个都会静默失败。
 * 所以每个方法都断言 path 与关键字段，而不是只断言返回值。
 */
class PayContractTest {

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
    fun `member groups fixture keeps four tiers of the paid group`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_user_groups.json")))

        val catalog = requireNotNull(api.memberGroups().data)
        val plans = catalog.list.getValue("3").toPlans()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/icciu_api.php/v1.user/groups", pathOf(request))
        // 价目表是"组 -> 四个时长价"，不是套餐数组；取键 "3" 的 VIP 会员组
        assertEquals(4, plans.size)
        assertEquals(listOf(1, 7, 30, 360), plans.map { it.priceYuan })
        assertEquals(listOf(1, 2, 3, 4), plans.map { it.id })
        assertEquals(PayPeriod.MONTH, plans[2].period)
        assertEquals(3, plans[2].groupId)
    }

    @Test
    fun `create order posts price and payment form and maps order code`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_user_order.json")))

        val payUrl = server.url("/pay").toString()
        val order = requireNotNull(api.createOrder("30", "alipay").data).toDomain(payUrl)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/icciu_api.php/v1.user/order", pathOf(request))
        assertEquals("price=30&payment=alipay", request.body.readUtf8())
        assertEquals("PAY20191120011606512230", order.orderCode)
        assertEquals("30", order.priceYuan)
        assertEquals(300, order.points)
        assertEquals(payUrl, order.payUrl)
    }

    @Test
    fun `pay tip keeps envelope msg and external recharge url`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_user_payTip.json")))

        val envelope = api.payTip()

        assertEquals("/icciu_api.php/v1.user/payTip", pathOf(server.takeRequest()))
        // 提示文案在响应壳 msg 上，data 里只有 url
        assertEquals("充值后自动到账，请勿重复支付", envelope.msg)
        assertEquals("https://example.com/user/recharge", envelope.data?.url)
    }

    @Test
    fun `gold tip keeps withdrawal threshold and ratio`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_user_goldTip.json")))

        val data = requireNotNull(api.goldTip().data)

        assertEquals("/icciu_api.php/v1.user/goldTip", pathOf(server.takeRequest()))
        assertEquals("最低提现20元", data.msg)
        assertEquals("当前可提现金额5000元，提现比例10金币=1元", data.info)
    }

    @Test
    fun `buy card posts only card pwd and maps the redeem result`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_user_buy.json")))

        val envelope = api.buyCard("CARD-123456")
        val redeem = requireNotNull(envelope.data).toDomain()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/icciu_api.php/v1.user/buy", pathOf(request))
        assertEquals("card_pwd=CARD-123456", request.body.readUtf8())
        // 老字段 data.msg 仍要能解析，部分调用方只看响应壳 code
        assertEquals("兑换成功", envelope.data?.msg)
        // 新字段：客户端据此按卡类型给提示（vip -> 天数，gold -> 金币）
        assertEquals(CardType.VIP, redeem.cardType)
        assertEquals(30, redeem.cardValue)
        assertEquals(3, redeem.groupId)
        assertEquals(1799999999L, redeem.membershipEnd)
    }

    @Test
    fun `buy card 400 keeps the business envelope readable`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("""{"code":400,"msg":"卡密无效或已使用","data":null}"""),
        )

        val failure = runCatching { api.buyCard("BAD") }.exceptionOrNull()
        val body = requireNotNull((failure as HttpException).response()?.errorBody()).string()

        // 仓库层靠这段 body 把 HTTP 400 还原成业务错误（ApiCall.toAppError）
        assertTrue(body.contains("\"code\":400"))
        assertTrue(body.contains("卡密无效或已使用"))
    }

    @Test
    fun `buy card 409 keeps the concurrency message readable`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("""{"code":409,"msg":"卡密已被使用","data":null}"""),
        )

        val failure = runCatching { api.buyCard("CARD-123456") }.exceptionOrNull()
        val body = requireNotNull((failure as HttpException).response()?.errorBody()).string()

        assertTrue(body.contains("\"code\":409"))
        assertTrue(body.contains("卡密已被使用"))
    }

    @Test
    fun `buy episode posts the six legacy form fields`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_user_buypopedom.json")))

        api.buyEpisode(
            mapOf(
                "type" to "4",
                "id" to "101",
                "sid" to "2",
                "nid" to "3",
                "mid" to "1",
                "pay_type" to "1",
            ),
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/icciu_api.php/v1.user/buypopedom", pathOf(request))
        val body = request.body.readUtf8()
        assertTrue(body.contains("type=4"))
        assertTrue(body.contains("id=101"))
        assertTrue(body.contains("sid=2"))
        assertTrue(body.contains("nid=3"))
        assertTrue(body.contains("mid=1"))
        assertTrue(body.contains("pay_type=1"))
    }

    @Test
    fun `order status query sends order code and maps the paid order`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_user_order_status.json")))

        val status = requireNotNull(api.orderStatus("PAY20191120011606512230").data).toDomain()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        // 与下单同路径，只有 HTTP 方法与 query 不同
        assertEquals("/icciu_api.php/v1.user/order", pathOf(request))
        // 签名由拦截器追加，所以只断言目标参数存在，不比较整串 query
        assertEquals(
            "PAY20191120011606512230",
            request.requestUrl?.queryParameter("order_code"),
        )
        assertEquals(1, status.orderStatus)
        assertTrue(status.isPaid)
        assertEquals("PAY20191120011606512230", status.orderCode)
        assertEquals("30.00", status.priceYuan)
        assertEquals(300, status.points)
        assertEquals("alipay", status.payType)
        assertEquals(1574183899L, status.payTime)
    }

    @Test
    fun `unpaid order status is reported through the model`() = runTest {
        server.enqueue(
            jsonResponse(
                """{"code":200,"msg":"success","data":{"order_code":"PAY1","order_status":0}}""",
            ),
        )

        val status = requireNotNull(api.orderStatus("PAY1").data).toDomain()

        assertEquals(0, status.orderStatus)
        assertFalse(status.isPaid)
        assertEquals("待支付", status.statusText)
    }

    @Test
    fun `upgrade group posts duration tier group id and order code`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_user_group.json")))

        val envelope = api.upgradeGroup("month", "3", "PAY202610251230001234")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/icciu_api.php/v1.user/group", pathOf(request))
        // 三个表单字段：long 是时长档位串(Kotlin 关键字冲突所以叫 period)，
        // order_code 让服务端校验「已支付 + 未开通过」并写幂等标记
        assertEquals(
            "long=month&group_id=3&order_code=PAY202610251230001234",
            request.body.readUtf8(),
        )
        assertEquals("会员开通成功", envelope.data?.msg)
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
