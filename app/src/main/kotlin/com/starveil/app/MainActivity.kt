package com.starveil.app

import android.os.Bundle
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.splashscreen.SplashScreen
import androidx.lifecycle.lifecycleScope
import com.starveil.app.navigation.StarveilNavHost
import com.starveil.app.ads.AdSplashGate
import com.starveil.core.common.AppResult
import com.starveil.core.common.AppearanceMode
import com.starveil.core.designsystem.LocalSkinSelection
import com.starveil.core.designsystem.AppearanceSelection
import com.starveil.core.designsystem.LocalAppearanceSelection
import com.starveil.core.designsystem.SkinSelection
import com.starveil.core.designsystem.StarveilTheme
import com.starveil.feature.player.PlayerEngineWarmUp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 单 Activity 架构。所有页面由 Compose Navigation 承载。
 *
 * 皮肤号必须在首帧前确定，否则会先闪一下默认色板；这里在组合里用 `produceState`
 * 取启动配置，取不到就退回默认皮肤（空串），与历史版本"缺字段直接退出进程"相反。
 */
/** 冷启动后多久预热播放引擎：等首帧与启动配置落定，别和它们抢主线程。 */
private const val PLAYER_WARMUP_DELAY_MS = 800L

/** 启动屏等首页的上限：接口卡住时不能永远停在品牌图上。 */
private const val SPLASH_MAX_MS = 3_000L

class MainActivity : ComponentActivity() {

    /** 与 Application 同一个容器；`attachActivity` 要用它挂前台 Activity。 */
    private val container by lazy { (application as StarveilApplication).container }

    override fun onResume() {
        super.onResume()
        container.attachActivity(this)
    }

    override fun onPause() {
        // 全屏广告只在前台展示；页面不可见时留引用等于持有一个已销毁的窗口。
        // 按实例摘：冷启动时开屏页可能在本页 resume 之后才销毁，它清的是自己。
        container.detachActivity(this)
        super.onPause()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        applyInitialSystemBars()
        val container = (application as StarveilApplication).container.also { holdSplashUntilHomeReady(splash) }
        setContent {
            // 首帧就要拿对皮肤：初值取上次生效的那一号（同步读 SharedPreferences），
            // 不能从空串起步——空串等于默认皮肤，网络回来才切成 3，用户看到的就是
            // "先默认、再变我的"。冷启动第一帧必须已经是上次的样子。
            val serverCode by produceState(
                initialValue = SplashSkinStore.read(this@MainActivity),
                container,
            ) {
                value = when (val startup = container.startupRepository.load()) {
                    is AppResult.Success -> startup.value.skinCode
                    is AppResult.Failure -> ""
                }
            }
            val localCode by container.themePreference.skinCode
                .collectAsStateWithLifecycle(initialValue = "")
            val skinCode = localCode.ifEmpty { serverCode }
            // 生效的皮肤顺手存一份同步值：下次冷启动的窗口底色靠它对齐
            LaunchedEffect(skinCode) { SplashSkinStore.write(this@MainActivity, skinCode) }
            val (appearance, darkTheme) = rememberAppearance(container)

            val scope = rememberCoroutineScope()
            CompositionLocalProvider(
                LocalSkinSelection provides SkinSelection(
                    current = localCode,
                    effective = skinCode,
                    select = { code -> scope.launch { container.themePreference.select(code) } },
                ),
                LocalAppearanceSelection provides AppearanceSelection(
                    current = appearance,
                    select = { mode -> scope.launch { container.themePreference.selectAppearance(mode) } },
                ),
            ) {
                StarveilTheme(skinCode = skinCode, darkTheme = darkTheme) {
                    StarveilNavHost(container = container)
                }
            }
        }
        warmUpPlayerEngineLater()
    }

    /**
     * 系统启动屏停在原地，等首页首个 Content 落地、开屏图也缓存好。
     *
     * 只等首页——其余页面自己会转圈。两件事都等，是因为开屏浮层要在启动屏退场的
     * 同一帧就铺满素材：图没落地就放行，用户会先看到一屏遮罩，倒计时却已经在走。
     *
     * 兜底 [SPLASH_MAX_MS]：接口或图床卡住时不能永远停在品牌图上。
     */
    private fun holdSplashUntilHomeReady(splash: SplashScreen) {
        splash.setKeepOnScreenCondition {
            !AdSplashGate.homeReady() || !AdSplashGate.splashArtReady()
        }
        lifecycleScope.launch { prefetchSplashArt() }
        lifecycleScope.launch {
            delay(SPLASH_MAX_MS)
            AdSplashGate.markHomeReady()
            AdSplashGate.markSplashArtReady()
        }
    }

    /** 把开屏图预热进 Coil 缓存。没有素材的站直接放行，不为一张不存在的图等待。 */
    private suspend fun prefetchSplashArt() {
        val url = AdSplashGate.splash(this)?.imageUrl.orEmpty()
        if (url.isNotBlank()) {
            withTimeoutOrNull(SPLASH_MAX_MS) {
                val request = ImageRequest.Builder(this@MainActivity).data(url).build()
                SingletonImageLoader.get(this@MainActivity).execute(request)
            }
        }
        AdSplashGate.markSplashArtReady()
    }

    /**
     * 系统栏先按上次的明暗排一次，Compose 组合完再按真实偏好覆盖：
     * 冷启动第一帧的图标颜色不该等到组合完才定。
     */
    private fun applyInitialSystemBars() {
        applySystemBars(SplashSkinStore.isDark(this, isNightMode()))
    }

    /**
     * 播放引擎第一次构建要几百毫秒，冷启动后第一次进短剧会正好撞上，
     * 卡出来的样子就是"dock 已经切过去、页面还没进来"。放首帧之后空闲时烤一次。
     */
    private fun warmUpPlayerEngineLater() {
        window.decorView.postDelayed({ PlayerEngineWarmUp.warm(this) }, PLAYER_WARMUP_DELAY_MS)
    }

    @Composable
    private fun rememberAppearance(container: AppContainer): Pair<AppearanceMode, Boolean> {
        // 明暗：初值同步读 SharedPreferences，避免"先按系统、再跳成用户选的"
        val appearance by container.themePreference.appearance.collectAsStateWithLifecycle(
            initialValue = SplashSkinStore.readAppearance(this@MainActivity),
        )
        val darkTheme = when (appearance) {
            AppearanceMode.SYSTEM -> isSystemInDarkTheme()
            AppearanceMode.LIGHT -> false
            AppearanceMode.DARK -> true
        }
        // 同步一份给启动页/开屏底色：它们在 Compose 之前就要用
        LaunchedEffect(appearance) {
            SplashSkinStore.writeAppearance(this@MainActivity, appearance)
        }
        // 页面背景可能是视频，状态栏图标由 AppScaffold 按路由和主题统一设置。
        return appearance to darkTheme
    }

    /** 系统当前是不是深色（用户没强制时用它兜底）。 */
    private fun isNightMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * 系统栏跟随明暗：深色底配浅色图标，浅色底配深色图标。
     *
     * 不能写死 `SystemBarStyle.dark`：浅色主题下状态栏图标会和背景融在一起。
     */
    private fun applySystemBars(darkTheme: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = barStyle(darkTheme),
            navigationBarStyle = barStyle(darkTheme),
        )
    }

    private fun barStyle(darkTheme: Boolean): SystemBarStyle =
        if (darkTheme) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            )
        }
}
