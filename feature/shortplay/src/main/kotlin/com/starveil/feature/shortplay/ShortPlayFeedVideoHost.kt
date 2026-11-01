package com.starveil.feature.shortplay

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

/**
 * 画面归哪一页：滑页期间锁在上一次落页，松手落定才交给新页。
 *
 * 归画面那一页自己画视频（见 [ShortPlayFeed] 的 `hostsVideo`），视频与同一页的模糊封面
 * 在同一个布局节点里，滑页时按构造一起走——不存在两套坐标系需要对齐，也就没有"差一帧"。
 *
 * 不能直接用 `settledPage`：它在滑页中就是 pager 打算落的那一页，拖过一半就已经翻页，
 * 画面会在屏幕正中跳整整一屏。锚点只在"没有滚动"时换，滑页全程画面跟着自己那一页走。
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
