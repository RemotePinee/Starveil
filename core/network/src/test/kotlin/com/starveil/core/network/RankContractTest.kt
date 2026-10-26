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
import kotlinx.serialization.json.Json
import com.starveil.core.network.dto.RankCatalogDto

/**
 * 榜单接口契约（旧 与 ，
 * 路径常量 、`:53`）。
 *
 * 两条契约不能混：
 * - `vodphb` 要四个查询参数，`data` 里只有平铺的 `list`（没有 total）；
 * - `vodPhbAll` 无参数，`data` 是 `{zhui, list}` 两组「分类 -> 影片」。
 */
class RankContractTest {

    /** 每个用例各自 new 一个 Json 会被判为冗余创建，提到类级共用。 */
    private val json = Json { ignoreUnknownKeys = true }

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
    fun `rank board sends order type page and limit`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_vod_vodphb.json")))

        api.vodRank(orderBy = "vod_hits_month desc", type = 1, page = 2, limit = 20)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/icciu_api.php/v1.vod/vodphb", pathOf(request))
        val url = requireNotNull(request.requestUrl)
        // order_by 是带空格和方向的排序串，不能被压成 vod_hits_month
        assertEquals("vod_hits_month desc", url.queryParameter("order_by"))
        assertEquals("1", url.queryParameter("type"))
        assertEquals("2", url.queryParameter("page"))
        assertEquals("20", url.queryParameter("limit"))
    }

    @Test
    fun `rank board fixture maps flat list and ignores total`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_vod_vodphb.json")))

        val vods = requireNotNull(api.vodRank("vod_hits desc", 1, 1).data).toDomain()

        assertEquals(2, vods.size)
        assertEquals(201, vods.first().id)
        assertEquals("榜单影片 A", vods.first().name)
        assertEquals("9.1", vods.first().score)
        assertEquals("更新至 12 集", vods[1].remarks)
    }

    @Test
    fun `rank catalog sends no parameters`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_vod_vodPhbAll.json")))

        api.vodRankAll()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/icciu_api.php/v1.vod/vodPhbAll", pathOf(request))
        val url = requireNotNull(request.requestUrl)
        assertNull(url.queryParameter("page"))
        assertNull(url.queryParameter("order_by"))
    }

    @Test
    fun `rank catalog fixture keeps promoted and groups apart`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_vod_vodPhbAll.json")))

        val catalog = requireNotNull(api.vodRankAll().data).toDomain()

        assertEquals(1, catalog.promoted.size)
        assertEquals("电影", catalog.promoted.first().typeName)
        assertEquals(301, catalog.promoted.first().vods.first().id)
        assertEquals("https://img.example.com/type/1.png", catalog.promoted.first().typeImage)

        // 夹具里第三条 vod_type_name 只有空白：映射层要丢掉，别让空分类流到 UI
        assertEquals(2, catalog.groups.size)
        assertTrue(catalog.groups.none { it.typeName.isBlank() })
        assertEquals("电视剧", catalog.groups.first().typeName)
        assertEquals(2, catalog.groups.first().vods.size)
        assertEquals(303, catalog.groups.first().vods[1].id)
        // 空分类与未知字段都要能落地，不能整包失败
        assertTrue(catalog.groups[1].vods.isEmpty())
        assertNull(catalog.groups[1].typeImage)
    }

    @Test
    fun `rank group accepts both vod_type and plain type keys`() {
        // 插件 Repo 曾经把分类字段写成 type_*，客户端只认 vod_type_* —— 结果是
        // typeId=0 + 空名字，界面上出现一条没有文字的标签。两套写法都必须能解析。
        val pluginStyle = json
            .decodeFromString<RankCatalogDto>(
                """{"list":[{"type_id":2,"type_name":"电视剧","type_img":"","vod_list":[]}]}""",
            )
        assertEquals(2, pluginStyle.groups.single().typeId)
        assertEquals("电视剧", pluginStyle.groups.single().typeName)

        val contractStyle = json
            .decodeFromString<RankCatalogDto>(
                """{"list":[{"vod_type_id":3,"vod_type_name":"综艺"}]}""",
            )
        assertEquals(3, contractStyle.groups.single().typeId)
        assertEquals("综艺", contractStyle.groups.single().typeName)
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
