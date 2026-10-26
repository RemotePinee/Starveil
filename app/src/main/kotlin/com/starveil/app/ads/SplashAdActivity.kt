package com.starveil.app.ads

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.widget.FrameLayout
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.starveil.app.MainActivity
import com.starveil.app.R
import com.starveil.app.StarveilApplication
import com.starveil.app.SplashSkinStore
import com.starveil.core.designsystem.starveilBackgroundArgb
import com.starveil.core.common.AppResult
import com.starveil.core.common.ad.AdSlot
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 冷启动开屏宿主：一个**只承载 SDK 开屏**的空白 Activity。
 *
 * 为什么单独一个 Activity，而不是在 [MainActivity] 的首帧上盖一层：
 * 聚合 SDK 的开屏是"往一个 ViewGroup 里 addView 自己的 View"，展示结束要
 * `removeAllViews()` 清场（见 `TradPlusAdProvider.awaitSplash`）。接管 MainActivity
 * 的 contentView 就等于拿整个界面当容器，清场时会把 Compose 根节点一起清掉。
 * 单独一个 Activity 可以随便被清空、直接 finish，主界面永远是干净的。
 *
 * 为什么不用 Compose 的 `AndroidView`：也能做，但冷启动阶段多一层组合没有收益，
 * 而且 `removeAllViews()` 会把 Compose 的根节点一起摘掉，反而要额外处理。
 *
 * 展示与否完全由后台配置决定，本类不做任何 UI 装饰：开屏素材、倒计时、跳过按钮
 * 都在 SDK 里（直投图片型的开屏仍由 `AdHost` 在首页画，两者互不影响）。
 */
class SplashAdActivity : ComponentActivity() {

    /** 与 Application 同一个容器。 */
    private val container by lazy { (application as StarveilApplication).container }

    /**
     * 跳首页只能发生一次：SDK 关闭、超时、没有配置三条路都会走到 [goMain]，
     * 谁先到谁生效。重复 startActivity 会在返回栈上叠出两个 MainActivity。
     */
    private val navigated = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // 由 core-splashscreen 接管系统启动画面：必须在 super.onCreate 之前安装，
        // 这样窗口底色一铺开就是皮肤色，不会先闪一下系统白/黑底。
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // 窗口背景是 splash_window（layer-list：底色 + 底部 lockup），必须在任何内容
        // 绘制前铺好，否则窗口已经按主题里的静态资源画过一帧了。
        applySplashWindowBackground()
        // 只有本地记过"平台开屏可用"（AdSplashGate 在启动配置落地时写入）才值得为它停一下。
        // 纯直投站（ad_platform=noop / 平台凭证不全）根本走不到 SDK 开屏：这里直接进首页，
        // 配置交给 MainActivity 自己拉——早先无条件等一次配置，冷启动首帧就是这么被拖慢的。
        if (!AdSplashGate.needed(this)) {
            goMain()
            return
        }
        // 容器铺满即可。SDK 拿 `android.R.id.content` 当根容器，这里给它的父层
        // 只承担"窗口里有一层 View 树"这件事，不画任何东西。
        setContentView(FrameLayout(this))
        // 竖屏锁定：开屏素材都是竖版，横屏下 SDK 会把素材拉伸变形。写在 manifest
        // 之外是为了让没有 manifest 声明的宿主（如测试构建）也保持同样的方向。
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        lifecycleScope.launch { runSplash() }
    }

    /**
     * 恢复会话 → 拉启动配置 → 换网关 → 展示开屏 → 进首页。
     *
     * 每一步失败都不阻断启动：开屏是"有就展示、没有就放行"的位，
     * 任何一环出问题都直接进 [MainActivity]，绝不让用户停在空白 Activity 上。
     */
    private suspend fun runSplash() {
        container.restoreSessionCookies()
        // 走到这里说明 AdSplashGate 记过"平台开屏可用"，这次配置就非拉不可：
        // adUnitId 与平台凭证只在启动配置里，没有它 SDK 无从展示。
        // 上限仍与 StarveilNavHost 同一口径（2 秒），超时按"没有配置"处理；
        // 被超时取消掉的请求，MainActivity 侧会再拉一次。
        val loaded = withTimeoutOrNull(CONFIG_TIMEOUT_MS) { container.startupRepository.load() }
        val config = (loaded as? AppResult.Success)?.value
        // 后台可能已经撤了平台凭证或开屏位：此时也没有可展示的东西，直接放行。
        if (config == null || !config.ads.platform.ready) {
            goMain()
            return
        }
        container.updateAdPlacements(config.ads)

        // 先挂宿主再展示：provider 展示时要现取当前前台 Activity。
        container.attachActivity(this)
        // 网关的 show 在"没有缓存"时立刻返回 NoFill（见 `SlotAd.present`），
        // 而冷启动进程里一定还没有开屏缓存，所以先触发一次加载：
        // 不 preload 的话，show 必然一步走完 NoFill 分支，开屏位永远不会展示。
        container.adGateway.preload(AdSlot.SPLASH)
        // 3 秒硬上限：SDK 拉取慢、回调丢失、用户一直不点跳过，都不能挡住进首页。
        // SDK 内部抛异常同样吞掉（provider 里对 SDK 调用也是 runCatching 收敛的）。
        runCatching {
            withTimeoutOrNull(SPLASH_TIMEOUT_MS) { container.adGateway.show(AdSlot.SPLASH) }
        }
        goMain()
    }

    /** 进入主界面并销毁自己。 */
    private fun goMain() {
        if (!navigated.compareAndSet(false, true)) return
        // 用户可能在开屏期间按返回退出：Activity 已在收尾时再跳转，
        // 会把"退出应用"变成"打开首页"。
        if (isFinishing || isDestroyed) return
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        // 只摘自己（[com.starveil.app.AppContainer.detachActivity]）：MainActivity
        // 通常在开屏页 onDestroy 之前就已经 resume 并把自己挂成宿主了，
        // 无条件置空会让首页一直以为"没有前台 Activity"，激励/插屏全部展示不了。
        container.detachActivity(this)
        super.onDestroy()
    }

    /**
     * 铺窗口背景：[R.drawable.splash_window] 是"底色 + 底部 lockup"的 layer-list。
     *
     * 只把第 0 层（底色）换成上次生效的皮肤色，logo 层原样保留：早先这里直接
     * `setBackgroundDrawable(ColorDrawable(...))` 整体换掉，logo 会被一起抹成纯色，
     * 首帧慢的时候就只剩一块空底色。
     */
    private fun applySplashWindowBackground() {
        val drawable = ContextCompat.getDrawable(this, R.drawable.splash_window) ?: return
        val skin = starveilBackgroundArgb(
            SplashSkinStore.read(this),
            SplashSkinStore.isDark(this, isNightMode()),
        )
        if (drawable is LayerDrawable) drawable.setDrawable(SKIN_LAYER_INDEX, ColorDrawable(skin))
        window.setBackgroundDrawable(drawable)
    }

    /** 系统当前是不是深色：用户没强制浅/深时用它兜底窗口底色。 */
    private fun isNightMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private companion object {
        /** 等启动配置的上限：与 `StarveilNavHost.STARTUP_TIMEOUT_MS` 取同一值。 */
        const val CONFIG_TIMEOUT_MS = 2_000L

        /** 等开屏展示结束的上限：超时就进首页，不赌 SDK 回调一定会来。 */
        const val SPLASH_TIMEOUT_MS = 3_000L

        /** splash_window 的 layer-list 里"底色"那一层的下标。 */
        const val SKIN_LAYER_INDEX = 0
    }
}
