package com.starveil.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.lerp

import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing
import kotlinx.coroutines.launch

/** 每组集数：6 列 × 5 行，正好一屏扫读，不会再把面板顶成一块大字区。 */
internal const val EPISODES_PER_RANGE = 30

/** 集数网格列数。与横屏选集（5 列）区分，竖屏 6 列能在一屏内放下 30 集。 */
internal const val EPISODE_ROW_COLUMNS = 6

/** 集数格高度：44dp。半屏时正好能露出三行左右。 */
internal val EPISODE_CELL_HEIGHT: Dp = Spacing.xxl + Spacing.md

/** 集数格间距，同时决定一组集数的总高度。 */
internal val EPISODE_CELL_GAP: Dp = Spacing.sm

/**
 * 分组标签固定宽度：96dp。
 *
 * 旧的平分写法在 300 集（10 组）时每组只剩三十几 dp，标签直接挤没了；
 * 固定宽度让每组都有完整的点按面积，放不下就横向滚动。
 */
private val RANGE_TAB_WIDTH: Dp = Spacing.xxl * 3

/** 指示器槽高：比指示条高一档，避免压到文字。 */
private val RANGE_TAB_INDICATOR_HEIGHT: Dp = Spacing.xs

/** 分组数：空列表为 0，其余按 30 一组向上取整。 */
internal fun episodeRangeCount(total: Int): Int = if (total <= 0) 0 else (total - 1) / EPISODES_PER_RANGE + 1

/** 某组内的真实集数；越界返回空表，不抛异常。 */
internal fun episodePage(episodes: List<VerticalEpisode>, page: Int): List<VerticalEpisode> {
    if (page !in 0 until episodeRangeCount(episodes.size)) return emptyList()
    return episodes.drop(page * EPISODES_PER_RANGE).take(EPISODES_PER_RANGE)
}

/**
 * pager 高度按真实集数算：短剧只有一两行时面板跟着收缩，
 * 不会为了等 30 集留出一整块空白。
 */
internal fun episodeGridHeight(total: Int): Dp {
    val visible = total.coerceIn(0, EPISODES_PER_RANGE)
    val rows = (visible + EPISODE_ROW_COLUMNS - 1) / EPISODE_ROW_COLUMNS
    if (rows == 0) return Spacing.none
    return EPISODE_CELL_HEIGHT * rows + EPISODE_CELL_GAP * (rows - 1)
}

/**
 * 某一组自己的真实行高：151–156 这种只有 1 行的小组给 1 行，
 * 不再按整季 30 集封顶成 5 行。
 */
internal fun episodePageGridHeight(episodes: List<VerticalEpisode>, page: Int): Dp =
    episodeGridHeight(episodePage(episodes, page).size)

/** 各组行高表，下标与页号一致；空列表返回空表。 */
internal fun episodePageGridHeights(episodes: List<VerticalEpisode>): List<Dp> =
    List(episodeRangeCount(episodes.size)) { episodePageGridHeight(episodes, it) }

/**
 * 拖动中的 pager 高度：把 `currentPage + currentPageOffsetFraction` 当成连续位置，
 * 在它落在的两组行高之间线性插值。
 *
 * `currentPageOffsetFraction` 跨页时会从 +0.5 翻成 -0.5，所以不能用
 * "当前页 → 下一页"的单一方向插值：那样会在翻页那一帧跳回上一组高度。
 * 取连续位置的 floor / ceil 两侧，跨过中点仍然连续；5 行拖到 1 行是逐帧收窄，
 * 不是松手瞬间裁掉 4 行。
 */
internal fun episodeGridHeightAt(heights: List<Dp>, page: Int, offsetFraction: Float): Dp {
    if (heights.isEmpty()) return Spacing.none
    val last = heights.lastIndex
    val position = (page + offsetFraction).coerceIn(0f, last.toFloat())
    val lower = position.toInt().coerceIn(0, last)
    val upper = (lower + 1).coerceAtMost(last)
    return lerp(heights[lower], heights[upper], (position - lower).coerceIn(0f, 1f))
}

/** 标签文案取该组首尾的真实集号：`1–30`，而不是按页号现算。 */
internal fun episodeRangeLabel(episodes: List<VerticalEpisode>, page: Int): String {
    val pageEpisodes = episodePage(episodes, page)
    if (pageEpisodes.isEmpty()) return ""
    return "${pageEpisodes.first().number}–${pageEpisodes.last().number}"
}

internal fun episodeRangeLabels(episodes: List<VerticalEpisode>): List<String> =
    List(episodeRangeCount(episodes.size)) { episodeRangeLabel(episodes, it) }

/**
 * 分组标签条：固定宽度 + 横向滚动，集数再多也不会把文字挤掉。
 *
 * - 点击让 pager 滑到对应页，指示器读同一个 [PagerState]；
 * - 指示器只在布局阶段取 `currentPage + currentPageOffsetFraction`，横拖集数时连续跟手，
 *   不是点一下整块瞬切，也没有给每格堆动画；
 * - 选中组落定后自动滚进可见区：本来就可见就一步不动，手指正在拖标签条时不抢手势。
 */
@Composable
internal fun EpisodeRangeTabs(
    pagerState: PagerState,
    labels: List<String>,
    modifier: Modifier = Modifier,
) {
    if (labels.size < 2) return
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val tabWidthPx = with(density) { RANGE_TAB_WIDTH.roundToPx() }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val viewportWidth = maxWidth
        val contentWidth = RANGE_TAB_WIDTH * labels.size
        // 组数少、整条放得下时居中，别全部缩在左边留一片空。
        val sideInset = ((viewportWidth - contentWidth) / 2).coerceAtLeast(Spacing.none)
        LaunchedEffect(pagerState.settledPage, labels.size) {
            if (scrollState.isScrollInProgress) return@LaunchedEffect
            val viewportPx = with(density) { viewportWidth.roundToPx() }
            val startPx = tabWidthPx * pagerState.settledPage
            val visibleStart = scrollState.value
            if (startPx >= visibleStart && startPx + tabWidthPx <= visibleStart + viewportPx) {
                return@LaunchedEffect
            }
            val centered = startPx - (viewportPx - tabWidthPx) / 2
            val maxScroll = with(density) { (contentWidth - viewportWidth).roundToPx() }.coerceAtLeast(0)
            scrollState.animateScrollTo(centered.coerceIn(0, maxScroll))
        }
        Column(
            modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState)
                .padding(start = sideInset, end = sideInset),
        ) {
            Row(Modifier.width(contentWidth)) {
                labels.forEachIndexed { page, label ->
                    RangeTab(
                        label = label,
                        selected = pagerState.currentPage == page,
                        onClick = { scope.launch { pagerState.animateScrollToPage(page) } },
                    )
                }
            }
            Box(
                modifier = Modifier.width(contentWidth).height(RANGE_TAB_INDICATOR_HEIGHT),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    Modifier.width(RANGE_TAB_WIDTH).height(Spacing.xxs).offset {
                        IntOffset(
                            (RANGE_TAB_WIDTH * (pagerState.currentPage + pagerState.currentPageOffsetFraction))
                                .roundToPx(),
                            0,
                        )
                    }.clip(AppShape.full).background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

@Composable
private fun RangeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier.width(RANGE_TAB_WIDTH).heightIn(min = Spacing.rowHeight)
            .clip(AppShape.small)
            .semantics { this.selected = selected }
            .verticalPress(onClick = onClick, role = Role.Tab),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = color,
            maxLines = 1,
        )
    }
}
