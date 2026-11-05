package com.starveil.feature.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import com.starveil.core.designsystem.component.blurredImageRequest
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.model.HomeBanner

/**
 * 轮播 pager 由首页宿主创建，背景层与轮播卡片共用同一个。
 *
 * 不这么做就得在背景层里再复制一份位移逻辑，两处状态一旦差一帧，
 * 背景会比卡片早/晚一张。null 表示没人提供，轮播自己建。
 */
internal val LocalHomeBannerPager = compositionLocalOf<PagerState?> { null }

/**
 * 底色铺到屏幕高度的这个比例：覆盖状态栏与轮播卡片，再往下就不该有颜色了。
 *
 * 这个值必须让底色**在轮播卡底边之前**就结束。之前是 0.42（1008px），卡底边在 982px，
 * 多出来的 26px 露出模糊图的暗部——深色下和 #252525 差不多看不出来，浅色下就是一道暗缝。
 */
private const val BACKDROP_HEIGHT_FRACTION = 0.40f

/** 往下到这里开始收回页面底色，保证任何设备上都不会在卡底边之外露出模糊图。 */
private const val BACKDROP_TAIL_START = 0.86f

/** 顶部压暗：深色要压得住状态栏白图标。 */
private const val BACKDROP_TOP_ALPHA = 0.72f

/** 浅色压得浅一档：压深了整块顶部发灰，压浅了白字读不出来。 */
private const val BACKDROP_TOP_ALPHA_LIGHT = 0.45f

/** 背景亮度低于它就算深色。 */
private const val DARK_LUMINANCE = 0.5f


/**
 * 首页顶部轮播的同图底色。
 *
 * 取当前页那一张图，走 [blurredImageRequest] 拿小尺寸模糊图铺满顶部，切页淡入，
 * 顶部的颜色于是跟着轮播走。明暗两种模式的差别只在"压暗还是压白"：
 * - 深色：压一层黑，状态栏白图标读得出来，往下收到透明；
 * - 浅色：压一层页面底色，把剧照调成淡彩，深色文字读得出来。
 *
 * 收尾一律收到**透明**而不是纯色：轮播卡顶部用同一个 alpha 接上，接缝两侧亮度一致，
 * 才不会在卡顶边说出现一条线。
 */
@Composable
internal fun HomeBannerBackdrop(
    banners: List<HomeBanner>,
    pager: PagerState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val pic = banners.getOrNull(pager.currentPage)?.pic.orEmpty()
    val request = remember(context, pic) {
        pic.takeIf(String::isNotBlank)?.let { blurredImageRequest(context, it) }
    }
    // 模糊两种模式都铺：这是首页的主角，切轮播顶部要跟着换色。
    // 明暗用背景色亮度判断，和下面 Card 的口径一致——读 LocalIsDarkTheme 曾拿到过旧值，
    // 结果背景是深色、分支却走浅色，压暗整层失效。
    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(BACKDROP_HEIGHT_FRACTION)
            .background(MaterialTheme.colorScheme.background),
    ) {
        Crossfade(
            targetState = request,
            animationSpec = tween(durationMillis = Motion.MediumMs),
            label = "home-backdrop",
        ) { target ->
            // 垫底必须用页面底色，不能用 scrimStrong：
            // 模糊图往下淡到透明后，露出的就是这层。深色下 #070809 和页面 #252525
            // 差得不多看不出来，浅色下它就是一条纯黑的横带——底部那条"缝"就是它。
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                if (target != null) {
                    AsyncImage(
                        model = target,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    // 顶部压暗到底、过渡全部交给图片自己的 alpha（卡片顶部有抠洞）。
                    // 压多深只跟明暗有关：深色底要压得住状态栏白图标，浅色底压太深就灰了。
                    Brush.verticalGradient(
                        0f to starveilExtraColors.scrimStrong.copy(
                            alpha = if (MaterialTheme.colorScheme.background.luminance() < DARK_LUMINANCE) {
                                BACKDROP_TOP_ALPHA
                            } else {
                                BACKDROP_TOP_ALPHA_LIGHT
                            },
                        ),
                        // 中段完全交给模糊图，末段收回页面底色：
                        // 底色一定在卡底边之前盖住，任何屏高上都不会露出模糊图的暗部。
                        BACKDROP_TAIL_START to Color.Transparent,
                        1f to MaterialTheme.colorScheme.background,
                    ),
                ),
        )
    }
}
