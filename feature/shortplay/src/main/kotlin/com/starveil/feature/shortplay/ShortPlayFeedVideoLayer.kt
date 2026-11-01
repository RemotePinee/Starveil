package com.starveil.feature.shortplay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged

/** 位移上限就是一屏；超过一屏的画面本来就在视口外。 */
private const val MAX_SLIDE_PAGES = 1f

/**
 * feed 级视频层跟着 pager 一起位移。
 *
 * 屏幕上那一帧永远是锚点页（上一次落页）渲出来的，所以位移基准就是锚点页：
 * 它的连续位置（`currentPage + currentPageOffsetFraction`）偏出多少，整块画面就平移多少。
 * 两处口径必须一次算完：这里只加"滑页"这一段，换集那一段由被注入的视频层自己加（`videoTranslationY`），
 * 叠在两层 `graphicsLayer` 上相加，谁也不重复加另一半。
 *
 * 落页即归零：松手落定时 [anchorPage] 已换成新页，连续位置正好等于它，位移回到 0。
 */
internal fun feedVideoTranslationY(
    anchorPage: Int,
    currentPage: Int,
    offsetFraction: Float,
    pageHeight: Float,
): Float {
    if (pageHeight <= 0f || !offsetFraction.isFinite()) return 0f
    val slidPages = currentPage + offsetFraction - anchorPage
    return -slidPages.coerceIn(-MAX_SLIDE_PAGES, MAX_SLIDE_PAGES) * pageHeight
}

/**
 * 画面归本页时才留透明：视频层只喂锚点页，其余页必须铺自己的封面。
 *
 * 没有 feed 级视频层（页面自己画视频）时一页都不归画面，页面全部垫封面。
 */
internal fun videoOwnsPage(hasVideoLayer: Boolean, page: Int, anchorPage: Int): Boolean =
    hasVideoLayer && page == anchorPage

/**
 * 画面归哪一页：滑页期间锁在上一次落页，松手落定才交给新页。
 *
 * 不能直接用 `settledPage`：它在滑页中就是 pager 打算落的那一页，拖过一半就已经翻页，
 * 位移会在屏幕正中跳整整一屏。锚点只在"没有滚动"时换，滑页全程的位移才是连续的。
 *
 * 页面据此决定要不要铺自己的封面：只有归画面的那一页留透明，其余页必须有底。
 */
@Composable
internal fun rememberFeedVideoAnchor(pager: PagerState): Int {
    var anchor by remember(pager) { mutableIntStateOf(pager.currentPage) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.isScrollInProgress to pager.currentPage }
            .collect { (scrolling, page) -> if (!scrolling) anchor = page }
    }
    return anchor
}

/**
 * 视频层由外层注入时铺在 pager 下面；null 表示没有 feed 级播放面，页面自己垫封面。
 *
 * 只负责"跟着当前页走"这一段位移：滑走的那一页带走自己的画面，滑进来的那一页由页面
 * 自己铺封面（见 [FeedPageContent] 的 `showsVideo`）。
 */
@Composable
internal fun FeedVideoLayer(
    layer: (@Composable (Boolean) -> Unit)?,
    covered: Boolean,
    anchorPage: Int,
    pager: PagerState,
) {
    if (layer == null) return
    // 尺寸只在布局时变，写进 state 后由 graphicsLayer 自己观察，滑页不会触发重组。
    val height = remember { mutableFloatStateOf(0f) }
    Box(
        // 先按 pager 位移、再裁到视口：越出的那半屏不能被画到 feed 之外。
        modifier = Modifier.fillMaxSize()
            .onSizeChanged { height.floatValue = it.height.toFloat() }
            .clipToBounds()
            .graphicsLayer {
                translationY = feedVideoTranslationY(
                    anchorPage = anchorPage,
                    currentPage = pager.currentPage,
                    offsetFraction = pager.currentPageOffsetFraction,
                    pageHeight = height.floatValue,
                )
            },
    ) {
        layer(covered)
    }
}
