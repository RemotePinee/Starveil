package com.starveil.core.network

import com.starveil.core.common.ad.AdPlacement
import com.starveil.core.common.ad.AdPlatformName
import com.starveil.core.common.ad.AdSlot
import com.starveil.core.model.SecondTab
import com.starveil.core.model.SkinId
import com.starveil.core.model.StartupConfig
import com.starveil.core.network.mapper.toDomain
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 启动接口契约。夹具字段名按后端真实返回写。
 *
 * 这组测试守的是"新版不杀进程"：在四个关键字段缺失时
 * `System.exit(0)`，新版必须降级并继续跑。
 */
class StartupContractTest {

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
    fun `startup fixture drives skin tab and layout`() = runTest {
        server.enqueue(json(fixture("v1_main_startup.json")))

        val config = requireNotNull(api.startup().data).toDomain()

        assertEquals("/icciu_api.php/v1.main/startup", server.takeRequest().path.orEmpty().substringBefore('?'))
        assertEquals(SkinId.BILIBILI, config.skin.skin)
        assertEquals(SecondTab.TOPIC, config.secondTab)
        assertEquals(StartupConfig.TWO_COLUMN_HOME, config.homeCardColumns)
        assertEquals(5, config.bannerStyleCode)
        assertTrue(config.topBarUsesBanner)
        assertTrue(config.playRecommendEnabled)
        // 夹具里 push_enabled 故意写成数字 1：后端两种写法都要能读
        assertTrue(config.pushEnabled)
        // 短剧分类号由后台下发（mac_type.type_id 各站不同），客户端只留兜底
        assertEquals(11, config.shortplayTypeId)
        assertEquals(2, config.payments.size)
        assertEquals("alipay", config.payments.first().code)
    }

    @Test
    fun `ad slots map to placements with creative`() = runTest {
        server.enqueue(json(fixture("v1_main_startup.json")))

        val ads = requireNotNull(api.startup().data).toDomain().ads

        assertEquals("SPLASH_ID", ads.splash.slotId)
        assertTrue(ads.splash.enabled)
        // 有图才算"能画"：位 ID 单独存在时留给以后接 SDK
        assertTrue(ads.splash.drawable)
        assertEquals("https://cdn.test/splash.png", ads.splash.imageUrl)
        assertEquals("https://test.invalid/click", ads.splash.clickUrl)
        assertEquals("开屏直投", ads.splash.title)
        // sdk_banner 的 status 是 0，即使 description 有值也不算开启
        assertFalse(ads.banner.enabled)
        // xunfei_appid 的 description 是空串，同样视为关闭
        assertEquals("", ads.startupHtml)
        assertEquals("APP_ID", ads.sdkApplicationId)
        assertEquals(8, ads.feed.feedInterval)
        assertEquals(5, ads.reward.rewardSeconds)
        assertTrue(ads.pause.drawable)
    }

    @Test
    fun `reward video url switches the reward slot to the direct video chain`() = runTest {
        server.enqueue(
            json(
                """{"code":200,"msg":"success","data":{"ads":{"ad_reward_video":"https://cdn.test/reward.mp4"}}}""",
            ),
        )

        val ads = requireNotNull(api.startup().data).toDomain().ads

        // 有地址 = 激励位可以走 session → 播 → complete；没有地址时同一个位退回图片倒计时
        assertTrue(ads.hasRewardVideo)
        assertEquals("https://cdn.test/reward.mp4", ads.rewardVideoUrl)
    }

    @Test
    fun `startup without reward video keeps the image fallback`() = runTest {
        server.enqueue(json(fixture("v1_main_startup.json")))

        val ads = requireNotNull(api.startup().data).toDomain().ads

        // 老后端不返回 ad_reward_video：字段落成空串，激励位仍按图片判定
        assertFalse(ads.hasRewardVideo)
        assertEquals("", ads.rewardVideoUrl)
    }

    @Test
    fun `ad interval and seconds are clamped`() = runTest {
        server.enqueue(
            json(
                """{"code":200,"msg":"success","data":{"ads":{""" +
                    """"sdk_informationflow":{"status":1,"description":"F","interval":99},""" +
                    """"sdk_excitation":{"status":1,"description":"R","seconds":0}}}}""",
            ),
        )

        val ads = requireNotNull(api.startup().data).toDomain().ads

        assertEquals(AdPlacement.MAX_INTERVAL, ads.feed.feedInterval)
        // 后端填 0 时退回默认，不会出现"0 秒自动完成"
        assertEquals(AdPlacement.DEFAULT_SECONDS, ads.reward.rewardSeconds)
        // 位 ID 有值但没有图片：画不出来，页面必须隐藏而不是留白
        assertFalse(ads.feed.drawable)
        assertFalse(ads.reward.drawable)
    }

    @Test
    fun `aggregator platform keeps its own credential shape`() = runTest {
        server.enqueue(json(fixture("v1_main_startup.json")))

        val platform = requireNotNull(api.startup().data).toDomain().ads.platform

        // 平台名决定客户端初始化哪套 SDK
        assertEquals(AdPlatformName.TRADPLUS, platform.name)
        assertTrue(platform.ready)
        // TradPlus 只有 appId，没有 appKey；五个位就是后台的 adUnitId
        assertEquals("TPX_APP", platform.tradPlus.appId)
        assertEquals("TPX_REWARD", platform.tradPlus.slotOf(AdSlot.REWARD))
        assertEquals("TPX_SPLASH", platform.tradPlus.slotOf(AdSlot.SPLASH))
        assertTrue(platform.tradPlus.initable)
    }

    @Test
    fun `unknown platform falls back to noop`() = runTest {
        server.enqueue(
            json(
                """{"code":200,"msg":"success","data":{"ads":{"ad_platform":"some-other-net"}}}""",
            ),
        )

        val platform = requireNotNull(api.startup().data).toDomain().ads.platform

        // 认不出的平台名不能猜：按 noop 处理，宁可用直投素材也不初始化来路不明的 SDK
        assertEquals(AdPlatformName.NOOP, platform.name)
        assertFalse(platform.ready)
    }

    @Test
    fun `missing theme field marks contract broken without exiting`() = runTest {
        server.enqueue(
            json(
                """{"code":200,"msg":"success","data":{"app_ui_tab_fun":"0","ui_home_data":"9"}}""",
            ),
        )

        val config = requireNotNull(api.startup().data).toDomain()

        assertTrue(config.skin.contractBroken)
        assertEquals(SkinId.DEFAULT, config.skin.skin)
        assertEquals(SecondTab.LIVE, config.secondTab)
        assertEquals(StartupConfig.DEFAULT_HOME_COLUMNS, config.homeCardColumns)
        assertNull(config.bannerStyleCode)
    }

    @Test
    fun `detail sections default to all visible`() = runTest {
        server.enqueue(json("""{"code":200,"msg":"success","data":{}}"""))

        val sections = requireNotNull(api.startup().data).toDomain().detailSections

        // 老后端不返回这个字段时按全显示处理：行为与加开关之前完全一致
        assertTrue(sections.recommend)
        assertTrue(sections.score)
        assertTrue(sections.comment)
    }

    @Test
    fun `detail sections honour zero flags`() = runTest {
        server.enqueue(
            json(
                """{"code":200,"msg":"success","data":{"detail_sections":""" +
                    """{"recommend":0,"score":1,"comment":0}}}""",
            ),
        )

        val sections = requireNotNull(api.startup().data).toDomain().detailSections

        // 只有显式 0 才算关；1 与缺字段都算开
        assertFalse(sections.recommend)
        assertTrue(sections.score)
        assertFalse(sections.comment)
    }

    @Test
    fun `local theme override wins over server value`() = runTest {
        server.enqueue(json("""{"code":200,"data":{"app_theme_ui":"1"}}"""))

        val config = requireNotNull(api.startup().data).toDomain(localThemeOverride = "3")

        assertEquals(SkinId.BILIBILI, config.skin.skin)
        assertTrue(config.skin.fromLocalOverride)
    }

    @Test
    fun `empty data is a business failure rather than a crash`() = runTest {
        server.enqueue(json("""{"code":403,"msg":"您未登陆","data":null}"""))

        val envelope = api.startup()

        assertEquals(403, envelope.code)
        assertNull(envelope.data)
    }

    private fun json(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/$name")) { "缺少测试夹具：$name" }.readText()
}
