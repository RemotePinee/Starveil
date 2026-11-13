package com.starveil.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import com.starveil.core.designsystem.component.BlurredCoverBackdrop
import com.starveil.core.model.HomeBanner
import kotlin.math.roundToInt

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


/** 首页第一项：轮播所在的位置。 */
private const val BANNER_ITEM_INDEX = 0

/**
 * 轮播这一项已经滚出视口多少像素。
 *
 * 抠洞钉在列表顶部、露出的就是模糊底；模糊底如果不跟着走，同一块屏幕位置每帧透出
 * 不同的像素，看着就是"底下漏出一块糊色"。
 */
@Composable
internal fun rememberBannerScrollPx(listState: LazyListState): Float {
    val scroll by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val banner = info.visibleItemsInfo.firstOrNull { it.index == BANNER_ITEM_INDEX }
            when {
                banner != null -> -banner.offset.toFloat()
                info.visibleItemsInfo.isNotEmpty() -> info.viewportSize.height.toFloat()
                else -> 0f
            }.coerceAtLeast(0f)
        }
    }
    return scroll
}

/**
 * 首页顶部轮播的同图底色。
 *
 * 取当前页那一张图，走 [BlurredCoverBackdrop] 拿小尺寸模糊图铺满顶部，顶部的颜色于是跟着轮播走。
 * 顶部的压暗不在这里：它由状态栏、顶栏与轮播抠洞那三层渐隐负责，
 * 这一层只负责"图后面那层颜色"，并跟着轮播卡一起上移。
 *
 * @param scrollOffsetPx 轮播已经滚出的像素：整层按它上移，和轮播卡保持同步
 */
@Composable
internal fun HomeBannerBackdrop(
    banners: List<HomeBanner>,
    pager: PagerState,
    modifier: Modifier = Modifier,
    /** 轮播已经滚出的像素：这一层跟轮播卡同速上移，和卡片保持同一块画面。 */
    scrollOffsetPx: Float = 0f,
) {
    if (banners.isEmpty()) {
        return
    }
    val pic = banners.getOrNull(pager.currentPage)?.pic?.takeIf(String::isNotBlank)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(BACKDROP_HEIGHT_FRACTION)
            // 跟轮播卡同速退场：卡片是 LazyColumn 第一项，位移就是滚动像素
            .offset { IntOffset(0, -scrollOffsetPx.roundToInt()) },
    ) {
        // 打底色交给 BlurredCoverBackdrop（它铺的是 scrimStrong）：糊图还没解出来的那一帧
        // 宁可黑一帧，也不能漏页面底色——浅色主题下那就是白的，深色剧照顶上会横出一条白带。
        BlurredCoverBackdrop(cover = pic, modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    // 末段收回页面底色：底色一定在卡底边之前盖住，
                    // 任何屏高上都不会露出模糊图的暗部。
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        BACKDROP_TAIL_START to Color.Transparent,
                        1f to MaterialTheme.colorScheme.background,
                    ),
                ),
        )
    }
}
