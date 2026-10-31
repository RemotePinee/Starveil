package com.starveil.core.network

import com.starveil.core.network.mapper.toDomain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import java.net.URL

/**
 * 网络层契约测试：走真实 OkHttp + Retrofit + kotlinx.serialization，
 * 只把服务端换成 MockWebServer。
 *
 * 夹具放在 src/test/resources/fixtures/ 下，字段名按后端真实返回写，
 * 不要为了迁就 DTO 改夹具——夹具是契约，DTO 是待验证方。
 */
class VodContractTest {

    private lateinit var server: MockWebServer
    private lateinit var api: StarveilApi
    private lateinit var searchApi: VodSearchApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = ApiClient(
            host = server.url("/").toString(),
            signature = ApiSignature(secret = "test-secret"),
        )
        api = client.api
        searchApi = client.searchApi
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `request path and query follow backend contract`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_vod_list.json")))

        api.listVod(type = 1, page = 2, limit = 20)

        val request = server.takeRequest()
        val url = requireNotNull(request.requestUrl)
        assertEquals("/icciu_api.php/v1.vod", request.path.orEmpty().substringBefore('?'))
        assertEquals("1", url.queryParameter("type"))
        assertEquals("2", url.queryParameter("page"))
        assertEquals("20", url.queryParameter("limit"))
    }

    @Test
    fun `signature interceptor injects apikey and keytime`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_vod_list.json")))

        api.listVod(type = 0, page = 1, limit = 20)

        val url = requireNotNull(server.takeRequest().requestUrl)
        val apiKey = requireNotNull(url.queryParameter("apikey"))
        val keyTime = requireNotNull(url.queryParameter("keytime"))
        // 后端的 md5 是 32 位小写十六进制，keytime 是秒级时间戳
        assertEquals(32, apiKey.length)
        assertEquals(10, keyTime.length)
        assertTrue(apiKey.all { it in "0123456789abcdef" })
    }

    @Test
    fun `list fixture parses into domain model`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_vod_list.json")))

        val envelope = api.listVod(type = 0, page = 1, limit = 20)
        val items = requireNotNull(envelope.data).list.map { it.toDomain() }

        assertEquals(200, envelope.code)
        assertEquals(2, items.size)
        assertEquals(101, items[0].id)
        assertEquals("8.6", items[0].score)
        // 第二个对象缺字段 + 带未知字段，必须仍然解析成功
        assertNull(items[1].poster)
    }

    /**
     * `type_name` 是插件按 type_id 批量查出来的分类名；后端不下发该字段，
     * 缺省必须是 null 且整条记录仍能解析（默认值兜底，不能因此报错）。
     */
    @Test
    fun `type name is carried when present and stays null for old backends`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_vod_list.json")))

        val items = requireNotNull(api.listVod(type = 0, page = 1, limit = 20).data)
            .list.map { it.toDomain() }

        assertEquals("短剧", items[0].typeName)
        assertNull(items[1].typeName)
    }

    @Test
    fun `detail fixture parses nested play sources and player info`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_vod_detail.json")))

        val vod = requireNotNull(api.vodDetail(vodId = 101).data).toDomain()
        val source = vod.playSources.single()
        // type_id 来自 VodBean.type_id，详情页拿它拉相关推荐
        assertEquals(3, vod.typeId)
        assertEquals("科幻片", vod.typeName)

        assertEquals(0, source.index)
        assertEquals("线路一", source.name)
        assertEquals(2, source.episodes.size)
        assertEquals(1, source.episodes.first().episodeNumber)
        assertEquals("https://cdn.example.com/1.m3u8", source.episodes.first().url)
        assertEquals(listOf("youku", "qq"), source.playerInfo.parseTargets)
        assertEquals(listOf("m3u8", "mp4"), source.playerInfo.directLinkPatterns)
        assertEquals(listOf("Referer", "User-Agent"), source.playerInfo.headerPatterns)
        assertEquals("https://example.com/", source.playerInfo.headers["Referer"])
        assertEquals("Mozilla/5.0", source.playerInfo.headers["User-Agent"])
    }

    @Test
    fun `http 400 keeps business envelope readable`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("""{"code":403,"msg":"您未登陆","data":null}"""),
        )

        val failure = runCatching { api.listVod(type = 0, page = 1, limit = 20) }.exceptionOrNull()

        assertTrue(failure is HttpException)
        val body = requireNotNull((failure as HttpException).response()?.errorBody()).string()
        // 仓库层靠这段 body 把 400 还原成业务错误，不能被 OkHttp 消费掉
        assertTrue(body.contains("\"code\":403"))
    }

    @Test
    fun `html body is reported as serialization failure`() = runTest {
        // 后端遇到不存在的方法会返回 HTML/脚本文本而不是 JSON
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body>404</body></html>"),
        )

        val failure = runCatching { api.listVod(type = 0, page = 1, limit = 20) }.exceptionOrNull()

        assertTrue(failure is SerializationException)
    }

    /**
     * 相关推荐复用 `v1.vod`：旧详情页的 `getSameTypeList` 就是这条路径
     * 参数是 type=type_id、page、limit（旧页 limit 固定 3）。
     */
    @Test
    fun `recommend request keeps type page and limit on v1 vod`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_vod_recommend.json")))

        api.listVod(type = 3, page = 1, limit = 6)

        val request = server.takeRequest()
        val url = requireNotNull(request.requestUrl)
        assertEquals("/icciu_api.php/v1.vod", request.path.orEmpty().substringBefore('?'))
        assertEquals("3", url.queryParameter("type"))
        assertEquals("1", url.queryParameter("page"))
        assertEquals("6", url.queryParameter("limit"))
    }

    @Test
    fun `recommend fixture maps type id into domain model`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_vod_recommend.json")))

        val items = requireNotNull(api.listVod(type = 3, page = 1, limit = 6).data)
            .list.map { it.toDomain() }

        assertEquals(3, items.size)
        assertEquals(201, items.first().id)
        assertEquals("同类型影片 A", items.first().name)
        assertEquals(3, items.first().typeId)
        // 缺字段的条目必须仍能解析，typeId 仍是后端下发的值
        assertEquals(3, items[1].typeId)
    }

    /**
     * 剧场里的关键词搜索 = `v1.vod?wd=` + `type`：加了关键词不能把分类丢掉，
     * 否则结果会从短剧范围漏成全站；不传分类时请求里也不能出现 `type`（老调用点行为不变）。
     */
    @Test
    fun `search keeps keyword and adds type only when scoped`() = runTest {
        server.enqueue(jsonResponse(fixture("v1_vod_list.json")))
        server.enqueue(jsonResponse(fixture("v1_vod_list.json")))

        searchApi.search(keyword = "总裁", limit = 20, type = 30)
        searchApi.search(keyword = "总裁", limit = 20)

        val scoped = requireNotNull(server.takeRequest().requestUrl)
        assertEquals("总裁", scoped.queryParameter("wd"))
        assertEquals("30", scoped.queryParameter("type"))

        val unscoped = requireNotNull(server.takeRequest().requestUrl)
        assertEquals("总裁", unscoped.queryParameter("wd"))
        assertNull(unscoped.queryParameter("type"))
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/$name")) { "缺少测试夹具：$name" }.readText()
}
