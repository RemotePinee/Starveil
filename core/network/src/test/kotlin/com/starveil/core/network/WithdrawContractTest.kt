package com.starveil.core.network

import com.starveil.core.network.mapper.toWithdrawTips
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 金币提现契约。
 *
 * 两个接口都不在支付链路上：`v1.user/goldTip` 只下文案（数字藏在中文里），
 * `v1.user/goldWithdrawApply` 四个表单字段一个都不能少。
 * 失败表现都是"成功但什么也没发生"，所以这里逐字段断言，而不是只断言返回值。
 */
class WithdrawContractTest {

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
    fun `gold tip text is parsed into threshold balance and ratio`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_user_goldTip.json")))

        val tips = requireNotNull(api.goldTip().data).toWithdrawTips()

        assertEquals("/icciu_api.php/v1.user/goldTip", pathOf(server.takeRequest()))
        // 后端把数字写在中文文案里：msg = 最低提现20元，info 里带可提现金额与比例
        assertEquals("最低提现20元", tips.hint)
        assertEquals(20, tips.minAmountYuan)
        assertEquals(5000, tips.availableYuan)
        assertEquals("当前可提现金额5000元，提现比例10金币=1元", tips.ratioText)
        assertTrue(tips.isBelowMinimum(19))
    }

    @Test
    fun `gold tip without numbers degrades to zero instead of failing`() = runTest {
        server.enqueue(
            jsonResponse("""{"code":200,"msg":"success","data":{"msg":"暂未开通","info":""}}"""),
        )

        val tips = requireNotNull(api.goldTip().data).toWithdrawTips()

        assertEquals(0, tips.minAmountYuan)
        assertEquals(0, tips.availableYuan)
        // 门槛为 0 表示后端没给门槛，页面不做下限拦截
        assertTrue(!tips.isBelowMinimum(1))
    }

    @Test
    fun `withdraw apply posts the four legacy form fields`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_user_goldWithdrawApply.json")))

        val envelope = api.withdrawApply(
            amount = "20",
            channel = "2",
            account = "wx_123",
            realName = "张三",
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/icciu_api.php/v1.user/goldWithdrawApply", pathOf(request))
        val body = request.body.readUtf8()
        assertTrue(body.contains("num=20"))
        assertTrue(body.contains("type=2"))
        assertTrue(body.contains("account=wx_123"))
        assertTrue(body.contains("realname=%E5%BC%A0%E4%B8%89"))
        assertEquals("提交成功", envelope.data?.info)
        // 没上传收款码时字段必须整个不出现，不能发一个空的 qrcode=
        assertTrue(!body.contains("qrcode"))
    }

    @Test
    fun `withdraw apply sends the qrcode path when one was uploaded`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_user_goldWithdrawApply.json")))

        api.withdrawApply(
            amount = "20",
            channel = "1",
            account = "alipay@test",
            realName = "张三",
            qrcode = "/upload/withdraw/12_1700000000.jpg",
        )

        val body = server.takeRequest().body.readUtf8()
        // 路径里的斜杠必须整体编码，否则后端只能拿到一段残缺目录名
        assertTrue(body.contains("qrcode=%2Fupload%2Fwithdraw%2F12_1700000000.jpg"))
    }

    @Test
    fun `withdraw qrcode upload posts a file part and returns the relative path`() = runTest {
        server.enqueue(
            jsonResponse(
                """{"code":200,"msg":"success","data":{"file":"/upload/withdraw/12_1700000000.jpg"}}""",
            ),
        )

        val part = MultipartBody.Part.createFormData(
            name = "file",
            filename = "qrcode.jpg",
            body = byteArrayOf(1, 2, 3).toRequestBody("image/jpeg".toMediaType()),
        )
        val envelope = api.uploadWithdrawQrcode(part)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/icciu_api.php/v1.upload/withdraw", pathOf(request))
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"file\""))
        assertTrue(body.contains("filename=\"qrcode.jpg\""))
        assertEquals("/upload/withdraw/12_1700000000.jpg", envelope.data?.file)
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
