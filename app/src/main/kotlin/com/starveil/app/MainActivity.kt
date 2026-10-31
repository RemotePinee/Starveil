package com.starveil.app

import android.os.Bundle
import android.view.ViewTreeObserver
import java.util.concurrent.atomic.AtomicBoolean
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import coil3.size.Size
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

/** 等 Compose 首帧的兜底：正常情况由 onPreDraw 放行，这里只防它永远不回调。 */
private const val COMPOSE_FALLBACK_MS = 800L

class MainActivity : ComponentActivity() {

    /** 与 Application 同一个容器；`attachActivity` 要用它挂前台 Activity。 */
    private val container by lazy { (application as StarveilApplication).container }


    /** Compose 是否已经画出第一帧。系统启动屏以它为放行条件。 */
    private val composedReady = AtomicBoolean(false)
    /** 是否离开过前台：区分"冷启动第一次 onResume"与"从后台回来"。 */
    private var wasBackgrounded = false

    override fun onResume() {
        super.onResume()
        container.attachActivity(this)
        if (wasBackgrounded) {
            wasBackgrounded = false
            // 回前台重拉启动配置：强制登录、广告位、登录方式都是后台可改项，只有进程内缓存
            // 的话，运营改了要等用户杀进程才生效。冷启动第一次不调——启动流程刚拉过。
            container.startupRepository.invalidate()
        }
    }

    override fun onPause() {
        wasBackgrounded = true
        // 全屏广告只在前台展示；页面不可见时留引用等于持有一个已销毁的窗口。
        // 按实例摘：冷启动时开屏页可能在本页 resume 之后才销毁，它清的是自己。
        container.detachActivity(this)
        super.onPause()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        applyInitialSystemBars()
        val container = (application as StarveilApplication).container.also { holdSplashUntilComposed(splash) }
        setContent { MainContent(container) }
        warmUpPlayerEngineLater()
    }

    /** 主题装配 + 导航宿主 + 第二屏覆盖层。抽出来是为了让 onCreate 保持在行数上限内。 */
    @Composable
    private fun MainContent(container: AppContainer) {
        val context = LocalContext.current
        // 首帧就要拿对皮肤：初值取上次生效的那一号（同步读 SharedPreferences），
        // 不能从空串起步——空串等于默认皮肤，网络回来才切成 3，用户看到的就是
        // "先默认、再变我的"。冷启动第一帧必须已经是上次的样子。
        val serverCode by produceState(initialValue = SplashSkinStore.read(context), container) {
            value = when (val startup = container.startupRepository.load()) {
                is AppResult.Success -> startup.value.skinCode
                is AppResult.Failure -> ""
            }
        }
        val localCode by container.themePreference.skinCode
            .collectAsStateWithLifecycle(initialValue = "")
        val skinCode = localCode.ifEmpty { serverCode }
        // 生效的皮肤顺手存一份同步值：下次冷启动的窗口底色靠它对齐
        LaunchedEffect(skinCode) { SplashSkinStore.write(context, skinCode) }
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
                Box(modifier = Modifier.fillMaxSize()) {
                    StarveilNavHost(container = container)
                    SplashBrandOverlay()
                }
            }
        }
    }

    /**
     * 系统启动屏只等 Compose 画出第一帧，随后交给第二屏（[SplashBrandOverlay]）。
     *
     * 品牌图是静态的，停久了只会显得卡住；第二屏有加载环和呼吸动画，
     * 同样的等待体感短得多。开屏图在这段时间后台预热。
     */
    private fun holdSplashUntilComposed(splash: SplashScreen) {
        splash.setKeepOnScreenCondition { !composedReady.get() }
        lifecycleScope.launch { prefetchSplashArt() }
        lifecycleScope.launch {
            delay(COMPOSE_FALLBACK_MS)
            composedReady.set(true)
        }
        window.decorView.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                    composedReady.set(true)
                    return true
                }
            },
        )
    }

    /** 把开屏图预热进 Coil 缓存。没有素材的站直接放行，不为一张不存在的图等待。 */
    private suspend fun prefetchSplashArt() {
        val url = AdSplashGate.splash(this)?.imageUrl.orEmpty()
        if (url.isNotBlank()) {
            withTimeoutOrNull(SPLASH_MAX_MS) {
                // size 必须和开屏浮层的 AsyncImage 一致，否则缓存 key 不同，
                // 这次预热不会被复用，用户看到的仍是当场下载。
                val request = ImageRequest.Builder(this@MainActivity)
                    .data(url)
                    .size(Size.ORIGINAL)
                    .build()
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
