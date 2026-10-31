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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.starveil.app.ads.AdSplashGate
import com.starveil.core.designsystem.token.Spacing
import kotlinx.coroutines.delay

/** logo 呼吸一次（透明度来回）的时长。 */
private const val BREATHE_MS = 1_400

/** 第二屏最短停留：首页来得太快也让动画走完一轮，否则看着像抽搐。 */
private const val BRAND_MIN_MS = 900L

/** 第二屏最多等多久（含图与首页）：超时就退场，不能把用户永久挡在这里。 */
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
 */
@Composable
internal fun SplashBrandLayer() {
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
    val composition by rememberLottieComposition(LottieCompositionSpec.Asset("splash_loading.json"))
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.splash_background)),
    ) {
        LottieAnimation(
            composition = composition,
            iterations = LottieConstants.IterateForever,
            modifier = Modifier
                .align(Alignment.Center)
                .size(Spacing.splashAnimation),
        )
        Image(
            bitmap = lockup,
            contentDescription = null,
            // FillBounds 对应窗口背景里的 bitmap gravity="fill"：
            // 两边都按 160×69dp 铺，不再让各自去推位图的固有尺寸。
            // 两边都按 320×138dp 铺，不再让各自去推位图的固有尺寸。
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
 * 第二屏的挂载与退场。
 *
 * 等两件事都就绪才退：首页画出内容、开屏素材进了缓存。退场动画播完才置位
 * [AdSplashGate.markBrandLayerGone]——开屏倒计时读这个栅栏，否则它在本层还盖着
 * 的时候就开始扣秒，用户真正看见广告时只剩两三秒。
 */
@Composable
internal fun SplashBrandOverlay() {
    var visible by remember { mutableStateOf(!AdSplashGate.homeReady()) }
    LaunchedEffect(Unit) {
        val started = SystemClock.elapsedRealtime()
        // 首页或开屏图就绪，或者等满上限就退场：后台把数据清空时首页会停在 Empty，
        // 没有这层兜底就一直卡在这里。Empty/Error 也算就绪（见 HomeViewModel）。
        while (!AdSplashGate.homeReady() || !AdSplashGate.splashArtReady()) {
            if (SystemClock.elapsedRealtime() - started > BRAND_MAX_MS) break
            delay(POLL_MS)
        }
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
        SplashBrandLayer()
    }
}
