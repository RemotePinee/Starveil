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

/** 底色铺到屏幕高度的这个比例：覆盖状态栏与轮播卡片，再往下就不该有颜色了。 */
private const val BACKDROP_HEIGHT_FRACTION = 0.42f

/** 顶部压暗的下限位置：保证浅色主题下状态栏那排白图标仍然读得出来。 */
private const val BACKDROP_SHADE_STOP = 0.1f

/** 从这里开始完全透明，交给页面底色收尾。 */
private const val BACKDROP_CLEAR_STOP = 0.34f

/**
 * 首页顶部轮播的同图底色。
 *
 * 取当前页那一张图，走 [blurredImageRequest] 拿小尺寸模糊图铺满顶部，
 * 上面再压一层竖直渐变：顶部够暗（状态栏图标恒白也能读），中段透出图片颜色，
 * 底部回到页面底色，和下面正文自然接上。
 *
 * 切页只做淡入（[Crossfade]），不跟手位移：跟手要么再复制一个 pager 的位移计算，
 * 要么每帧重算模糊，两个都不划算。
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
    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(BACKDROP_HEIGHT_FRACTION),
    ) {
        Crossfade(
            targetState = request,
            animationSpec = tween(durationMillis = Motion.MediumMs),
            label = "home-backdrop",
        ) { target ->
            Box(Modifier.fillMaxSize().background(starveilExtraColors.scrimStrong)) {
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
                    Brush.verticalGradient(
                        0f to starveilExtraColors.heroScrimEnd,
                        BACKDROP_SHADE_STOP to starveilExtraColors.playerControlBackground,
                        BACKDROP_CLEAR_STOP to Color.Transparent,
                        1f to MaterialTheme.colorScheme.background,
                    ),
                ),
        )
    }
}
