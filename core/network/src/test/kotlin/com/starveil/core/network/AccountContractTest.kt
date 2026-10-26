package com.starveil.core.network

import com.starveil.core.network.mapper.toDomain
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 账号类接口契约：注册、两种短信验证码、找回密码、用户详情。
 *
 * 方法 / 路径 / 字段名逐个对照后端返回，夹具字段名按后端
 * 真实返回写。这些接口全靠表单名和 query 名区分语义，
 * 字段写错不会报错、只会静默失败，所以每个字段都要断言。
 */
class AccountContractTest {

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
    fun `register posts the four required form fields`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_auth_success.json")))

        api.register(
            userName = "demo",
            password = "pwd123",
            passwordConfirm = "pwd123",
            uid = "channel_01",
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/icciu_api.php/v1.auth/register", pathOf(request))
        val body = request.body.readUtf8()
        assertTrue(body.contains("user_name=demo"))
        assertTrue(body.contains("user_pwd=pwd123"))
        // user_pwd2 与 user_pwd 必须同时出现，后端只校验 pwd2
        assertTrue(body.contains("user_pwd2=pwd123"))
        assertTrue(body.contains("uid=channel_01"))
    }

    @Test
    fun `register code hits register sms path with phone account`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_auth_success.json")))

        api.sendRegisterCode(phone = "15682522328")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/icciu_api.php/v1.auth/registerSms", pathOf(request))
        val url = requireNotNull(request.requestUrl)
        assertEquals("15682522328", url.queryParameter("to"))
        assertEquals("phone", url.queryParameter("ac"))
    }

    @Test
    fun `reset password sends every findpass query field`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_auth_success.json")))

        api.resetPassword(
            phone = "15682522328",
            password = "12345678",
            passwordConfirm = "12345678",
            code = "755554",
        )

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/icciu_api.php/v1.auth/findpass", pathOf(request))
        val url = requireNotNull(request.requestUrl)
        assertEquals("15682522328", url.queryParameter("to"))
        assertEquals("12345678", url.queryParameter("user_pwd"))
        assertEquals("12345678", url.queryParameter("user_pwd2"))
        assertEquals("755554", url.queryParameter("code"))
        assertEquals("phone", url.queryParameter("ac"))
        assertEquals("2", url.queryParameter("type"))
    }

    @Test
    fun `reset code uses the findpasssms path`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_auth_success.json")))

        api.sendResetCode(phone = "15682522328")

        val request = server.takeRequest()
        assertEquals("/icciu_api.php/v1.auth/findpasssms", pathOf(request))
        assertEquals("15682522328", requireNotNull(request.requestUrl).queryParameter("to"))
    }

    @Test
    fun `user detail fixture maps group and keeps both point balances`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_user_detail.json")))

        val profile = requireNotNull(api.userInfo().data).toDomain()

        assertEquals(72, profile.userId)
        assertEquals("demo", profile.userName)
        assertEquals("示例昵称", profile.nickName)
        assertEquals("默认会员", profile.groupName)
        // points 与 user_points 是后端两个独立字段，不能合并
        assertEquals(0, profile.points)
        assertEquals(10, profile.userPoints)
        assertEquals("29.00", profile.gold)
        // 0 表示未开通/永久，不能当 1970 渲染
        assertEquals(0L, profile.memberEndTime)
        assertEquals("128", profile.viewCount)
    }

    @Test
    fun `user detail without group still parses`() = runTest {
        server.enqueue(
            jsonResponse(
                """{"code":200,"msg":"success","data":{"user_id":0,"user_name":"游客"}}""",
            ),
        )

        val profile = requireNotNull(api.userInfo().data).toDomain()

        assertEquals(0, profile.userId)
        assertEquals("游客", profile.userName)
        // 游客态没有 group 段，groupName 必须是空串而不是崩溃
        assertEquals("", profile.groupName)
        assertEquals("0", profile.gold)
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
