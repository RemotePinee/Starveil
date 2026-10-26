package com.starveil.core.network

import com.starveil.core.network.mapper.toDomain
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `v1.vod/types` 的 `extend`（筛选维度）契约测试。
 *
 * 后端刚加这个字段，三种形态都会出现：完整、整块缺失、只给一个键。
 * 夹具按后端真实返回写，DTO 是待验证方——解析失败不能靠改夹具掩盖。
 */
class VodTypesContractTest {

    private lateinit var server: MockWebServer
    private lateinit var typeApi: VodTypeApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        typeApi = ApiClient(
            host = server.url("/").toString(),
            signature = ApiSignature(secret = "test-secret"),
        ).typeApi
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `types path and full extend map into the domain model`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_vod_types.json")))

        val envelope = typeApi.vodTypes()
        val request = server.takeRequest()
        assertEquals("/icciu_api.php/v1.vod/types", request.path.orEmpty().substringBefore('?'))

        val types = requireNotNull(envelope.data).list.map { it.toDomain() }
        val movie = types.first()
        assertEquals("电影", movie.name)
        assertEquals(listOf("大陆", "香港"), movie.extend.area)
        assertEquals(listOf("2026", "2025"), movie.extend.year)
        assertEquals(listOf("喜剧", "动作"), movie.extend.classNames)
        assertEquals(listOf("国语", "粤语"), movie.extend.lang)
    }

    /** `extend: {}`（分类没有配置筛选维度）：四个维度都空，页面整行不渲染。 */
    @Test
    fun `empty extend object degrades to an empty extend`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_vod_types.json")))

        val types = requireNotNull(typeApi.vodTypes().data).list.map { it.toDomain() }

        assertTrue(types[1].extend.isEmpty)
        assertEquals(emptyList<String>(), types[1].extend.area)
    }

    /** 老后端 / 未配置的分类没有 `extend` 键，整条记录仍必须解析成功。 */
    @Test
    fun `missing extend degrades to an empty extend`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_vod_types_extend_partial.json")))

        val types = requireNotNull(typeApi.vodTypes().data).list.map { it.toDomain() }

        assertTrue(types[0].extend.isEmpty)
        // `extend: null` 与缺键同义
        assertTrue(types[2].extend.isEmpty)
    }

    /** 只回 `area` 时其它三个维度必须是空列表，不能退化成 null 或占位值。 */
    @Test
    fun `area only extend keeps the other dimensions empty`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_vod_types_extend_partial.json")))

        val variety = requireNotNull(typeApi.vodTypes().data).list[1].toDomain()

        // 顺带覆盖清洗：后端留的空串与首尾空格的重复项都不该变成一颗空白胶囊
        assertEquals(listOf("大陆"), variety.extend.area)
        assertEquals(emptyList<String>(), variety.extend.year)
        assertEquals(emptyList<String>(), variety.extend.classNames)
        assertEquals(emptyList<String>(), variety.extend.lang)
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/$name")) { "缺少测试夹具：$name" }.readText()
}
