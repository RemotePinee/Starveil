package com.starveil.core.data

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.model.Session
import com.starveil.core.network.StarveilApi
import com.starveil.core.network.dto.ApiEnvelopeDto
import com.starveil.core.network.dto.FavoriteDto
import com.starveil.core.network.dto.PageDto
import com.starveil.core.network.dto.ScoreDto
import com.starveil.core.network.dto.StartupDto
import com.starveil.core.network.dto.UserInfoDto
import com.starveil.core.network.dto.VodDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * 登录、注册与找回密码。契约见旧 与 。
 */
class DefaultAuthRepositoryTest {

    @Test
    fun `successful login stores the session from cookies`() = runTest {
        val api = FakeAuthApi(response = ApiEnvelopeDto(code = 200, msg = "success", data = JsonPrimitive("ok")))
        api.cookies["user_id"] = "42"
        api.cookies["user_name"] = "demo"
        api.cookies["user_check"] = "abc123"
        val store = FakeSessionStore()

        val result = repository(api, store).login(userName = "demo", password = "pwd")

        assertTrue(result is AppResult.Success)
        assertEquals("42", (result as AppResult.Success).value.userId)
        assertEquals("42", store.saved?.userId)
        assertEquals("abc123", store.saved?.userCheck)
    }

    @Test
    fun `business rejection keeps the store untouched`() = runTest {
        val api = FakeAuthApi(response = ApiEnvelopeDto(code = 403, msg = "账号或密码错误", data = null))
        val store = FakeSessionStore()

        val error = (repository(api, store).login("demo", "bad") as AppResult.Failure).error

        assertTrue(error is AppError.Business)
        assertEquals(403, (error as AppError.Business).code)
        assertEquals("账号或密码错误", (error as AppError.Business).message)
        assertNull(store.saved)
    }

    @Test
    fun `code 200 without user id cookie is treated as failure`() = runTest {
        // 在这种情况下会"登录成功但状态为空"，下一个请求 403，用户看到的是莫名报错
        val api = FakeAuthApi(response = ApiEnvelopeDto(code = 200, msg = "success", data = JsonPrimitive("ok")))
        val store = FakeSessionStore()

        val error = (repository(api, store).login("demo", "pwd") as AppResult.Failure).error

        assertTrue(error is AppError.Business)
        assertTrue((error as AppError.Business).message.contains("user_id"))
        assertNull(store.saved)
    }

    @Test
    fun `network failure is surfaced as network error`() = runTest {
        val api = FakeAuthApi(failure = java.io.IOException("timeout"))

        val error = (repository(api, FakeSessionStore()).login("demo", "pwd") as AppResult.Failure).error

        assertTrue(error is AppError.Network)
    }

    @Test
    fun `restore returns the stored session and ignores empty ones`() = runTest {
        val store = FakeSessionStore()
        val repository = repository(FakeAuthApi(), store)

        assertNull(repository.restore())

        store.saved = Session("42", "demo", "", "1", "VIP", "abc", "")
        assertEquals("42", repository.restore()?.userId)

        store.saved = Session("", "", "", "", "", "", "")
        assertNull(repository.restore())
    }

    @Test
    fun `logout clears the local session only`() = runTest {
        val store = FakeSessionStore()
        store.saved = Session("42", "demo", "", "", "", "abc", "")

        repository(FakeAuthApi(), store).logout()

        assertNull(store.saved)
    }

    @Test
    fun `register success does not persist a session`() {
        runTest {
            val api = FakeAuthApi(response = ApiEnvelopeDto(code = 200, msg = "success", data = JsonPrimitive("1")))
            val store = FakeSessionStore()

            val result = repository(api, store).register(
                userName = "demo",
                password = "pwd",
                passwordConfirm = "pwd",
                uid = "channel_01",
            )

            assertTrue(result is AppResult.Success)
            // 注册响应里即使带 cookie 也不等于登录态，注册后还要再登录一次
            assertNull(store.saved)
        }
    }

    @Test
    fun `register rejection keeps the backend code`() {
        runTest {
            val api = FakeAuthApi(response = ApiEnvelopeDto(code = 409, msg = "用户名已存在", data = null))

            val result = repository(api, FakeSessionStore())
                .register("demo", "pwd", "pwd", "channel_01")

            val error = (result as AppResult.Failure).error
            assertTrue(error is AppError.Business)
            assertEquals(409, (error as AppError.Business).code)
            assertEquals("用户名已存在", (error as AppError.Business).message)
        }
    }

    @Test
    fun `reset password success returns unit`() {
        runTest {
            val api = FakeAuthApi(response = ApiEnvelopeDto(code = 200, msg = "success", data = JsonPrimitive("1")))

            val result = repository(api, FakeSessionStore())
                .resetPassword("15682522328", "12345678", "12345678", "755554")

            assertTrue(result is AppResult.Success)
        }
    }

    @Test
    fun `network failure on reset code is a network error`() {
        runTest {
            val api = FakeAuthApi(failure = java.io.IOException("timeout"))

            val result = repository(api, FakeSessionStore()).sendResetCode("15682522328")

            assertTrue((result as AppResult.Failure).error is AppError.Network)
        }
    }

    private fun repository(api: FakeAuthApi, store: SessionStore) =
        DefaultAuthRepository(api = api, cookies = { api.cookies }, store = store)
}

private class FakeSessionStore : SessionStore {
    var saved: Session? = null

    override suspend fun load(): Session? = saved

    override suspend fun save(session: Session) {
        saved = session
    }

    override suspend fun clear() {
        saved = null
    }
}

private class FakeAuthApi(
    private val response: ApiEnvelopeDto<JsonElement>? = null,
    private val failure: Exception? = null,
) : StubStarveilApi() {

    val cookies = mutableMapOf<String, String>()

    override suspend fun login(userName: String, password: String): ApiEnvelopeDto<JsonElement> {
        failure?.let { throw it }
        return requireNotNull(response)
    }

    override suspend fun register(
        userName: String,
        password: String,
        passwordConfirm: String,
        uid: String,
        code: String,
    ): ApiEnvelopeDto<JsonElement> = authResult()

    override suspend fun sendRegisterCode(phone: String, ac: String): ApiEnvelopeDto<String> =
        textResult()

    override suspend fun resetPassword(
        phone: String,
        password: String,
        passwordConfirm: String,
        code: String,
        ac: String,
        type: String,
    ): ApiEnvelopeDto<String> = textResult()

    override suspend fun sendResetCode(phone: String, ac: String): ApiEnvelopeDto<String> =
        textResult()

    override suspend fun userInfo(): ApiEnvelopeDto<UserInfoDto> = unsupported()

    override suspend fun favoriteList(
        page: Int,
        limit: Int,
        ulogType: Int,
    ): ApiEnvelopeDto<PageDto<FavoriteDto>> = unsupported()

    override suspend fun favoriteAdd(
        vodId: String,
        moduleId: String,
        type: String,
    ): ApiEnvelopeDto<String> = unsupported()

    override suspend fun favoriteRemove(ids: String, type: String): ApiEnvelopeDto<String> =
        unsupported()

    /** 登录/注册：data 形态不固定，用 JsonElement 接。 */
    private fun authResult(): ApiEnvelopeDto<JsonElement> {
        failure?.let { throw it }
        return requireNotNull(response)
    }

    /** 其它接口仍是字符串信封；`failure` 照常抛出，网络错误用例依赖它。 */
    private fun textResult(): ApiEnvelopeDto<String> {
        failure?.let { throw it }
        return ApiEnvelopeDto(code = 200, msg = "success", data = "ok")
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException("not used in this test")

    override suspend fun startup(): ApiEnvelopeDto<StartupDto> = error("not used")

    override suspend fun listVod(
        type: Int,
        page: Int,
        limit: Int,
        classFilter: String,
        area: String,
        year: String,
        lang: String,
        by: String,
    ): ApiEnvelopeDto<PageDto<VodDto>> = error("not used")

    override suspend fun vodDetail(vodId: Int, relLimit: Int): ApiEnvelopeDto<VodDto> = error("not used")
}
