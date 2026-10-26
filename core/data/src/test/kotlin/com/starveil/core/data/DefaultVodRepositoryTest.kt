package com.starveil.core.data

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.model.VodOrder
import com.starveil.core.network.StarveilApi
import com.starveil.core.network.dto.ApiEnvelopeDto
import com.starveil.core.network.dto.FavoriteDto
import com.starveil.core.network.dto.PageDto
import com.starveil.core.network.dto.ScoreDto
import com.starveil.core.network.dto.StartupDto
import com.starveil.core.network.dto.UserInfoDto
import com.starveil.core.network.dto.VodDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import kotlinx.serialization.json.JsonElement

/**
 * 仓库层契约测试：错误收敛是 UI 不崩的前提，必须先测。
 *
 * 注意：测试方法必须用 block body（`fun x() { runTest { ... } }`）。
 * `runTest` 返回 TestResult，写成表达式体会触发 JUnit4 的
 * "initializationError: Method should be void"。
 */
class DefaultVodRepositoryTest {

    @Test
    fun `success response is mapped to domain model`() {
        runTest {
            val api = FakeApi(
                listResponse = ApiEnvelopeDto(
                    code = 200,
                    msg = "success",
                    data = PageDto(
                        total = 1,
                        page = 1,
                        limit = 20,
                        list = listOf(VodDto(id = 42, name = "测试影片")),
                    ),
                ),
            )

            val result = DefaultVodRepository(api = api).list()

            assertTrue(result is AppResult.Success)
            val items = (result as AppResult.Success).value
            assertEquals(1, items.size)
            assertEquals(42, items.first().id)
            assertEquals("测试影片", items.first().name)
        }
    }

    @Test
    fun `business code with empty data is wrapped as business error`() {
        runTest {
            val api = FakeApi(
                listResponse = ApiEnvelopeDto(code = 403, msg = "您未登陆", data = null),
            )

            val result = DefaultVodRepository(api = api).list()

            val error = (result as AppResult.Failure).error
            assertTrue(error is AppError.Business)
            assertEquals(403, (error as AppError.Business).code)
            assertEquals("您未登陆", (error as AppError.Business).message)
        }
    }

    @Test
    fun `io exception is wrapped as network error`() {
        runTest {
            val result = DefaultVodRepository(FakeApi(listFailure = IOException("timeout"))).list()

            assertTrue((result as AppResult.Failure).error is AppError.Network)
        }
    }

    @Test
    fun `http 400 with business envelope is unwrapped as business error`() {
        runTest {
            val body = """{"code":500,"msg":"影片不存在","data":null}"""
                .toResponseBody("application/json".toMediaType())
            val api = FakeApi(listFailure = HttpException(Response.error<Any>(400, body)))

            val error = (DefaultVodRepository(api = api).list() as AppResult.Failure).error

            assertTrue(error is AppError.Business)
            assertEquals(500, (error as AppError.Business).code)
            assertEquals("影片不存在", (error as AppError.Business).message)
        }
    }

    @Test
    fun `http error without json body stays http error`() {
        runTest {
            val body = "<html>500</html>".toResponseBody("text/html".toMediaType())
            val api = FakeApi(listFailure = HttpException(Response.error<Any>(502, body)))

            val error = (DefaultVodRepository(api = api).list() as AppResult.Failure).error

            assertTrue(error is AppError.Http)
            assertEquals(502, (error as AppError.Http).code)
        }
    }

    @Test
    fun `serialization failure is wrapped as serialization error`() {
        runTest {
            val api = FakeApi(listFailure = SerializationException("unexpected json"))

            val error = (DefaultVodRepository(api = api).list() as AppResult.Failure).error

            assertTrue(error is AppError.Serialization)
        }
    }

    /*
     * 相关推荐走的是 `v1.vod` 同类型列表，
     * 所以仓库侧只需验证三件事：参数透传、成功映射、失败收敛与空列表。
     */

    @Test
    fun `recommend forwards type and maps the same type list`() {
        runTest {
            val api = FakeApi(
                listResponse = ApiEnvelopeDto(
                    code = 200,
                    msg = "success",
                    data = PageDto(
                        total = 1,
                        page = 1,
                        limit = 6,
                        list = listOf(VodDto(id = 9, name = "推荐影片", typeId = 3)),
                    ),
                ),
            )

            val result = DefaultVodRepository(api = api).recommend(typeId = 3)

            val items = (result as AppResult.Success).value
            assertEquals(1, items.size)
            assertEquals(9, items.first().id)
            assertEquals(3, items.first().typeId)
            assertEquals(3, api.lastType)
            assertEquals(1, api.lastPage)
            assertEquals(VodRepository.DEFAULT_RECOMMEND_LIMIT, api.lastLimit)
        }
    }

    @Test
    fun `recommend business failure is wrapped as business error`() {
        runTest {
            val api = FakeApi(listResponse = ApiEnvelopeDto(code = 403, msg = "您未登陆", data = null))

            val error = (DefaultVodRepository(api = api).recommend(typeId = 3) as AppResult.Failure).error

            assertTrue(error is AppError.Business)
            assertEquals(403, (error as AppError.Business).code)
        }
    }

    @Test
    fun `recommend empty list is a successful empty result`() {
        runTest {
            val api = FakeApi(
                listResponse = ApiEnvelopeDto(
                    code = 200,
                    msg = "success",
                    data = PageDto<VodDto>(list = emptyList()),
                ),
            )

            val result = DefaultVodRepository(api = api).recommend(typeId = 3)

            assertTrue(result is AppResult.Success)
            assertEquals(0, (result as AppResult.Success).value.size)
        }
    }

    @Test
    fun `filter forwards screening parameters and order whitelist`() {
        runTest {
            val api = FakeApi(
                listResponse = ApiEnvelopeDto(
                    code = 200,
                    msg = "success",
                    data = PageDto(total = 1, page = 2, limit = 20, list = listOf(VodDto(id = 7))),
                ),
            )

            val result = DefaultVodRepository(api = api).filter(
                type = 3,
                page = 2,
                area = "大陆",
                year = "2026",
                lang = "国语",
                className = "喜剧",
                order = VodOrder.HITS,
            )

            assertTrue(result is AppResult.Success)
            assertEquals(3, api.lastType)
            assertEquals(2, api.lastPage)
            assertEquals("大陆", api.lastArea)
            assertEquals("2026", api.lastYear)
            assertEquals("国语", api.lastLang)
            assertEquals("喜剧", api.lastClassName)
            assertEquals(VodOrder.HITS, api.lastOrder)
        }
    }

    @Test
    fun `filter falls back to the default order for an unknown value`() {
        runTest {
            val api = FakeApi(listResponse = ApiEnvelopeDto(code = 200, data = PageDto<VodDto>()))

            DefaultVodRepository(api = api).filter(order = "drop table")

            assertEquals(VodOrder.DEFAULT, api.lastOrder)
        }
    }
}

/**
 * 手写 fake 而非 mock 框架：默认每个方法都抛 UnsupportedOperationException，只有列表被覆写。
 */
private class FakeApi(
    private val listResponse: ApiEnvelopeDto<PageDto<VodDto>>? = null,
    private val listFailure: Exception? = null,
) : StubStarveilApi() {

    var lastType: Int? = null
        private set

    var lastPage: Int? = null
        private set

    var lastLimit: Int? = null
        private set

    var lastArea: String? = null
        private set

    var lastYear: String? = null
        private set

    var lastLang: String? = null
        private set

    var lastClassName: String? = null
        private set

    var lastOrder: String? = null
        private set

    override suspend fun startup(): ApiEnvelopeDto<StartupDto> =
        throw UnsupportedOperationException("not used in this test")

    override suspend fun login(userName: String, password: String): ApiEnvelopeDto<JsonElement> =
        throw UnsupportedOperationException("not used in this test")

    override suspend fun listVod(
        type: Int,
        page: Int,
        limit: Int,
        classFilter: String,
        area: String,
        year: String,
        lang: String,
        by: String,
    ): ApiEnvelopeDto<PageDto<VodDto>> {
        lastType = type
        lastPage = page
        lastLimit = limit
        lastArea = area
        lastYear = year
        lastLang = lang
        lastClassName = classFilter
        lastOrder = by
        listFailure?.let { throw it }
        return requireNotNull(listResponse)
    }

    override suspend fun vodDetail(
        vodId: Int,
        relLimit: Int,
    ): ApiEnvelopeDto<VodDto> = error("not used in this test")

    override suspend fun register(
        userName: String,
        password: String,
        passwordConfirm: String,
        uid: String,
        code: String,
    ): ApiEnvelopeDto<JsonElement> = unsupported()

    override suspend fun sendRegisterCode(phone: String, ac: String): ApiEnvelopeDto<String> =
        unsupported()

    override suspend fun resetPassword(
        phone: String,
        password: String,
        passwordConfirm: String,
        code: String,
        ac: String,
        type: String,
    ): ApiEnvelopeDto<String> = unsupported()

    override suspend fun sendResetCode(phone: String, ac: String): ApiEnvelopeDto<String> =
        unsupported()

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

    private fun unsupported(): Nothing = throw UnsupportedOperationException("not used in this test")
}
