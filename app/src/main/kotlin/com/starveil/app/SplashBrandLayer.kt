package com.starveil.app

// 用户要求暂时隐藏开屏底部 logo，保留以便恢复：这一组 import 只服务于 lockup 与呼吸动画，
// 恢复下方被注释掉的代码时把它们一并放开即可。
// import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
// import androidx.compose.animation.core.RepeatMode
// import androidx.compose.animation.core.animateFloat
// import androidx.compose.animation.core.infiniteRepeatable
// import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
// import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
// import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
// import androidx.compose.ui.graphics.asImageBitmap
// import androidx.compose.ui.layout.ContentScale
// import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import com.airbnb.lottie.RenderMode
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionResult
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.starveil.app.ads.AdSplashGate
import com.starveil.core.designsystem.token.Spacing
import kotlinx.coroutines.delay

// 用户要求暂时隐藏底部 lockup，保留以便恢复：呼吸时长先留着，恢复动画时直接用。
// /** logo 呼吸一次（透明度来回）的时长。 */
// private const val BREATHE_MS = 1_400

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
 *
 * 底部 lockup 目前按用户要求暂时隐藏（见下方注释掉的代码）；上面这段定位约定保留，
 * 恢复时两屏之间那个 logo 才还是"同一个元素在延续"。
 */
@Composable
internal fun SplashBrandLayer() {
    // 用户要求暂时隐藏开屏底部 logo，保留以便恢复：
    // 底部的 bitmap 与它的呼吸动画整段注掉，不删——lockup 的尺寸与定位约定还在
    // Spacing / splash_window.xml 里，恢复时把这段放开就回到原来的效果。
    // val context = LocalContext.current
    // val lockup = remember {
    //     BitmapFactory.decodeResource(context.resources, R.drawable.ic_splash_lockup)
    //         .asImageBitmap()
    // }
    // val breathe by rememberInfiniteTransition(label = "splash-breathe").animateFloat(
    //     initialValue = 0.62f,
    //     targetValue = 1f,
    //     animationSpec = infiniteRepeatable(tween(BREATHE_MS), RepeatMode.Reverse),
    //     label = "splash-breathe-alpha",
    // )
    val compositionResult = rememberLottieComposition(LottieCompositionSpec.Asset("splash_loading.json"))
    val composition by compositionResult
    SplashLottieDiagnostics(compositionResult)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.splash_background)),
    ) {
        LottieAnimation(
            composition = composition,
            iterations = 1,
            // 播放速度：素材 3 秒，开屏等不起，2 倍速约 1.5 秒一遍
            speed = 2f,
            // 素材里 12 个形状元素是 merge paths。不显式开启时 Lottie 在**解析期**就把它们
            // 丢掉（MergePaths.toContent 返回 null，ContentGroup 过滤掉），图层只剩零碎几何，
            // 168dp 里看着就是没动画。这是解析开关，换渲染模式救不回来，必须开。
            enableMergePaths = true,
            // 用默认的硬件渲染：素材只有 8 个形状图层、没有蒙版，硬件路径完全够用，
            // 软件渲染只会多占 CPU。之前加它是因为误判成「硬件不支持」，实际是素材里
            // 那个非法颜色字段让 composition 解析失败，与渲染模式无关。
            modifier = Modifier
                .align(Alignment.Center)
                .size(Spacing.splashAnimation),
        )
        // 用户要求暂时隐藏开屏底部 lockup，保留以便恢复：整段注掉，不要删。
        // Image(
        //     bitmap = lockup,
        //     contentDescription = null,
        //     // FillBounds 对应窗口背景里的 bitmap gravity="fill"：
        //     // 两边都按 160×69dp 铺，不再让各自去推位图的固有尺寸。
        //     // 两边都按 320×138dp 铺，不再让各自去推位图的固有尺寸。
        //     contentScale = ContentScale.FillBounds,
        //     alpha = breathe,
        //     modifier = Modifier
        //         .align(Alignment.BottomCenter)
        //         .padding(bottom = Spacing.xxl)
        //         .size(width = Spacing.splashLogoWidth, height = Spacing.splashLogoHeight),
        // )
    }
}

/**
 * 临时诊断：分开"解析失败"与"渲染失败"。
 *
 * [LottieAnimation] 在 `composition.duration == 0f` 时直接返回一个空 Box，
 * 解析失败和设备上的绘制异常在屏幕上是同一块空白，只有这两个数字能把它们分开。
 * 项目禁止裸 `Log.*`（`verifySecurityBaseline` 会拦），这里走 `println` 到 logcat。
 * 排查完可以整块删除。
 */
@Composable
private fun SplashLottieDiagnostics(result: LottieCompositionResult) {
    LaunchedEffect(result) {
        if (!BuildConfig.DEBUG) return@LaunchedEffect
        // await 而不是读当前值：读到的是"还没加载完"的 null，两个状态分不开。
        val loaded = runCatching { result.await() }
        val composition = loaded.getOrNull()
        println(
            "SplashLottie duration=${composition?.duration} " +
                "layers=${composition?.layers?.size} " +
                "warnings=${composition?.warnings} " +
                "error=${loaded.exceptionOrNull()}",
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
