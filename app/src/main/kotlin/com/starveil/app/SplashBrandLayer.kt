package com.starveil.app

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieAnimatable
import com.airbnb.lottie.compose.rememberLottieComposition
import com.starveil.app.ads.AdSplashGate
import com.starveil.core.designsystem.token.Spacing
import kotlinx.coroutines.delay

/** logo 呼吸一次（透明度来回）的时长。 */
private const val BREATHE_MS = 1_400

/**
 * 第二屏最短停留：动画播完前也不许一闪而过。
 *
 * 只在素材解析失败时才成为实际的时间窗——正常路径由「动画播完」把着，比它长得多。
 */
private const val BRAND_MIN_MS = 900L

/**
 * 第二屏最多等多久：**只兜 Lottie 卡死**，正常路径到不了。
 *
 * 3.0s 动画（`res/raw/splash_movie_theatre.json`：fr 32 × op 96）+ 0.32s 淡出 ≈ 3.4s，
 * 素材解析与首帧都在剩下 1.6s 里。换更长的动画要同步抬高这个值，否则会切在动画中段。
 */
private const val BRAND_MAX_MS = 5_000L

/** 第二屏淡出时长。 */
private const val BRAND_FADE_MS = 320

/** 等首页/开屏图就绪的轮询间隔。 */
private const val POLL_MS = 40L


/**
 * 第二屏：系统启动屏退场后的品牌等待层。
 *
 * 背景色与底部 lockup 的定位必须和 `splash_window.xml` 完全一致（贴底 32dp、水平居中、
 * 原始像素不缩放），两屏之间这个 logo 才是"同一个元素在延续"——位置差几像素，
 * 交接那一下就会看出来跳了。
 *
 * [onAnimationEnd] 是中间品牌动画的回调：播完一次、或素材解析失败都会触发，上层拿它当
 * "可以淡出"的栅栏（见 [SplashBrandOverlay]）。
 */
@Composable
internal fun SplashBrandLayer(onAnimationEnd: () -> Unit) {
    val context = LocalContext.current
    val lockup = remember {
        BitmapFactory.decodeResource(context.resources, R.drawable.ic_splash_lockup)
            .asImageBitmap()
    }
    val breathe by rememberInfiniteTransition(label = "splash-breathe").animateFloat(
        initialValue = 0.62f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(BREATHE_MS), RepeatMode.Reverse),
        label = "splash-breathe-alpha",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.splash_background)),
    ) {
        SplashBrandAnimation(
            onAnimationEnd = onAnimationEnd,
            modifier = Modifier.align(Alignment.Center),
        )
        Image(
            bitmap = lockup,
            contentDescription = null,
            // FillBounds 对应窗口背景里的 bitmap gravity="fill"：
            // 两边都按 160×69dp 铺，不再让各自去推位图的固有尺寸。
            contentScale = ContentScale.FillBounds,
            alpha = breathe,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = Spacing.xxl)
                .size(width = Spacing.splashLogoWidth, height = Spacing.splashLogoHeight),
        )
    }
}

/**
 * 开屏品牌动画：`res/raw/splash_movie_theatre.json`，630×630 方形素材，只播一次。
 *
 * 素材是外部导出的 json，解析失败时 [LottieAnimation] 一帧都不画、也没有完成回调，所以
 * `isFailure` 也算"结束"：退化成底色 + 底部 lockup 继续往下走。把启动流程卡死在这里比少
 * 一个动画严重得多。
 */
@Composable
private fun SplashBrandAnimation(onAnimationEnd: () -> Unit, modifier: Modifier = Modifier) {
    val compositionResult = rememberLottieComposition(
        // 放 assets 而不是 res/raw：release 开了 isShrinkResources，R8 会把 R.raw 的
        // 引用内联成常量，资源 shrinker 看不到引用就把文件删了（debug 包有、release 没有）。
        LottieCompositionSpec.Asset("splash_movie_theatre.json"),
    )
    val animatable = rememberLottieAnimatable()
    // 播完（或确认解析失败）才通知上层：倒计时栅栏读的就是这个信号
    LaunchedEffect(compositionResult.value, compositionResult.isFailure) {
        val composition = compositionResult.value
        if (composition == null) {
            if (compositionResult.isFailure) onAnimationEnd()
            return@LaunchedEffect
        }
        animatable.animate(composition = composition, iterations = 1)
        onAnimationEnd()
    }
    LottieAnimation(
        composition = compositionResult.value,
        progress = { animatable.progress },
        // 方形素材在任意高度的容器里按原比例居中，不拉伸变形
        contentScale = ContentScale.Fit,
        modifier = modifier.size(Spacing.splashAnimation),
    )
}

/**
 * 退场判定：**动画播完是硬条件**，首页与开屏素材只在播完之后才参与判定。
 *
 * [BRAND_MAX_MS] 是唯一的提前出口，只在 Lottie 卡死（`animate()` 一直不返回）时命中；
 * 正常路径 3.0s 动画 + 0.32s 淡出远小于它。
 */
private fun brandLayerCanLeave(startedAt: Long, animationDone: Boolean): Boolean {
    val timedOut = SystemClock.elapsedRealtime() - startedAt > BRAND_MAX_MS
    val contentReady = AdSplashGate.homeReady() && AdSplashGate.splashArtReady()
    return timedOut || (animationDone && contentReady)
}

/**
 * 第二屏的挂载与退场。
 *
 * **每次冷启动都无条件显示**：不看首页是否已就绪、也不看有没有快照，Lottie 从第 0 帧播到
 * 结束，播完才允许退场（见 [brandLayerCanLeave]）。上一轮按 `homeReady()` 跳过整层的写法
 * 在这里是致命的——`AdSplashGate.homeReady` 是进程级、从不复位的标记，首页有本地快照时
 * 它在本层组合前就是 true，于是 `SplashBrandLayer` 根本没被组合，`rememberLottieComposition`
 * 不曾运行，一秒动画都放不出来。
 *
 * 退场动画播完才置位 [AdSplashGate.markBrandLayerGone]——开屏倒计时读这个栅栏，否则它在
 * 本层还盖着的时候就开始扣秒，用户真正看见广告时只剩两三秒。
 */
@Composable
internal fun SplashBrandOverlay() {
    // 无条件显示：动画没播完就不退场，首页快慢都不影响这一层出现
    var visible by remember { mutableStateOf(true) }
    // 初值必须是 false：只有 animate() 真的返回、或确认解析失败，才允许置 true
    var animationDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val started = SystemClock.elapsedRealtime()
        // 动画播完是硬栅栏；之后再看首页与开屏素材。等满上限才强制放行（Lottie 卡死兜底）。
        while (!brandLayerCanLeave(started, animationDone)) delay(POLL_MS)
        val remaining = BRAND_MIN_MS - (SystemClock.elapsedRealtime() - started)
        if (remaining > 0) delay(remaining)
        visible = false
        // 淡出走完再放开倒计时：这一层还盖着的时候用户看不到广告
        delay(BRAND_FADE_MS.toLong())
        AdSplashGate.markBrandLayerGone()
    }
    AnimatedVisibility(
        visible = visible,
        // enter 必须显式关掉：默认是 fadeIn + expandVertically，内容会从 0 高度往上展开，
        // BottomCenter 的锚点在动画期间一直在动，过渡里就会看到 logo 位置在跳。
        enter = EnterTransition.None,
        exit = fadeOut(tween(BRAND_FADE_MS)),
    ) {
        SplashBrandLayer(onAnimationEnd = { animationDone = true })
    }
}
