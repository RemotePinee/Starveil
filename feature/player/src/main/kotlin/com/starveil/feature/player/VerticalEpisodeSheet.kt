package com.starveil.feature.player

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Star
import com.adamglin.phosphoricons.regular.Star
import com.starveil.core.designsystem.component.DampedSheetScope
import com.starveil.core.designsystem.component.StarveilDampedSheet
import com.starveil.core.designsystem.interaction.HapticKind
import com.starveil.core.designsystem.interaction.rememberHaptics
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing

/**
 * 选集面板。宿主仅在 showEpisodes 时挂载；预览选集的导航仍由 actions 负责。
 *
 * 外壳交给 [StarveilDampedSheet]：真实阻尼拖拽、默认停半屏、上拉铺满安全高、
 * 下拉先折回半屏再往下才关闭；顶部不进状态栏，底部避开导航栏与 IME，
 * handle 也由宿主统一提供。
 *
 * 收藏条不进滚动容器，位置由 [EpisodeSheetFrame] 钉在**当前可见视口**的底边：
 * 宿主只把整张 surface 往下平移，半屏时 surface 下半截本就在屏幕外，
 * 在 Column 里贴底会跟着一起看不见。
 */
@Composable
internal fun VerticalEpisodeSheet(stage: VerticalPlayStage) {
    val episodes = stage.state.episodes
    val currentPosition = episodes.indexOfFirst { it.index == stage.state.currentIndex }.coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = rangeOfPosition(currentPosition, episodes.size),
        pageCount = { episodeRangeCount(episodes.size).coerceAtLeast(1) },
    )
    // 播放进度在面板打开期间前进时，分组跟着走到当前集所在的组。
    LaunchedEffect(currentPosition, episodes.size) {
        val target = rangeOfPosition(currentPosition, episodes.size)
        if (target != pagerState.currentPage && target in 0 until pagerState.pageCount) {
            pagerState.animateScrollToPage(target)
        }
    }
    StarveilDampedSheet(onDismissRequest = stage.actions.onToggleEpisodes) {
        EpisodeSheetFrame(stage, pagerState, this, Modifier.fillMaxWidth())
    }
}

private fun rangeOfPosition(position: Int, total: Int): Int {
    val lastRange = (episodeRangeCount(total) - 1).coerceAtLeast(0)
    return (position / EPISODES_PER_RANGE).coerceIn(0, lastRange)
}

/**
 * pager 视口高度跟着**当前组**的真实行高走，在测量阶段读分页位置：
 * 拖动时按相邻两组行高逐帧插值，收窄/长高都是连续的。
 *
 * 高度不能写回组合阶段的 `Modifier.height(...)`：那会在拖动时每帧重组整张面板。
 * 读的是 [PagerState.currentPage] / [PagerState.currentPageOffsetFraction]，
 * 变化只触发重新测量。
 */
private fun Modifier.episodePagerHeight(
    heights: List<Dp>,
    pagerState: PagerState,
): Modifier = layout { measurable, constraints ->
    val heightPx = episodeGridHeightAt(
        heights = heights,
        page = pagerState.currentPage,
        offsetFraction = pagerState.currentPageOffsetFraction,
    ).roundToPx().coerceIn(constraints.minHeight, constraints.maxHeight)
    val placeable = measurable.measure(
        constraints.copy(minHeight = heightPx, maxHeight = heightPx),
    )
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}

/**
 * 面板骨架：滚动内容 + 钉在可见视口底部的收藏条。
 *
 * 可见高度只从宿主的 [DampedSheetScope.visibleHeight] 取：它已扣掉状态栏、导航栏、
 * IME 与 handle，且只在**测量阶段**读取——拖拽只触发重新测量，不回写组合阶段的
 * `Modifier.height`，也就没有「内容高 ↔ 锚点偏移」的往返回环。
 * body 的上限是「可见高 - 收藏条高」，收藏条压在可见底边，frame 汇报可见高。
 */
@Composable
private fun EpisodeSheetFrame(
    stage: VerticalPlayStage,
    pagerState: PagerState,
    sheet: DampedSheetScope,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier,
        content = {
            EpisodeSheetBody(stage, pagerState)
            SheetFavoriteButton(stage)
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val limitPx = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE
        val visiblePx = sheet.visibleHeight.roundToPx().coerceIn(0, limitPx)
        val footer = measurables[1].measure(Constraints.fixedWidth(width))
        val frameHeight = visiblePx.coerceAtLeast(footer.height)
        val body = measurables[0].measure(
            Constraints(minWidth = width, maxWidth = width, maxHeight = frameHeight - footer.height),
        )
        layout(width, frameHeight) {
            body.placeRelative(0, 0)
            footer.placeRelative(0, frameHeight - footer.height)
        }
    }
}

/** 唯一的滚动容器：高度按内容收缩，长简介和大字号把上方内容推走而不是把集数挤没。 */
@Composable
private fun EpisodeSheetBody(
    stage: VerticalPlayStage,
    pagerState: PagerState,
    modifier: Modifier = Modifier,
) {
    val episodes = stage.state.episodes
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        EpisodeSheetSeriesHeader(stage.state, Modifier.padding(horizontal = Spacing.lg))
        if (stage.state.description.isNotBlank()) {
            EpisodeSheetDescription(stage.state, Modifier.padding(horizontal = Spacing.lg))
        }
        if (episodes.isEmpty()) {
            Text(
                text = "暂无可选集数",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xl),
            )
        } else {
            EpisodeRangeTabs(
                pagerState = pagerState,
                labels = episodeRangeLabels(episodes),
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
            val gridHeights = remember(episodes) { episodePageGridHeights(episodes) }
            HorizontalPager(
                state = pagerState,
                // 各页网格上对齐：短组不再浮在高视口正中。
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg)
                    .episodePagerHeight(gridHeights, pagerState),
            ) { page ->
                EpisodeRangePage(episodes, page, stage)
            }
        }
    }
}

@Composable
private fun SheetFavoriteButton(stage: VerticalPlayStage) {
    val favorite = stage.options.isFavorite
    val pending = stage.options.pendingAction == "收藏"
    val interaction = remember { MutableInteractionSource() }
    val scale = verticalPressScale(interaction)
    val haptics = rememberHaptics()
    val container = if (favorite) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.primary
    }
    val content = if (favorite) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onPrimary
    }
    val stateText = when {
        pending -> "收藏处理中"
        favorite -> "已收藏"
        else -> "未收藏"
    }
    val label = favoriteButtonLabel(pending, favorite)
    // 收藏条整条刷底色：它钉在可见底边上，顶边与滚动内容相接，
    // 不铺满会和面板底色之间出现一条色带。
    val barColor = MaterialTheme.colorScheme.surface
    val barEdge = Modifier.fillMaxWidth().drawBehind { drawRect(color = barColor) }
    Button(
        onClick = { haptics(HapticKind.Tick); stage.actions.onAction("收藏") },
        enabled = stage.options.pendingAction == null,
        interactionSource = interaction,
        shape = AppShape.full,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        modifier = barEdge.padding(horizontal = Spacing.lg, vertical = Spacing.md)
            .heightIn(min = Spacing.rowHeight).graphicsLayer { scaleX = scale; scaleY = scale }
            .semantics { stateDescription = stateText },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pending) {
                VerticalActionLoading(Modifier.size(IconSize.item))
            } else {
                Icon(
                    imageVector = if (favorite) {
                        PhosphorIcons.Fill.Star
                    } else {
                        PhosphorIcons.Regular.Star
                    },
                    contentDescription = null,
                    modifier = Modifier.size(IconSize.item),
                )
            }
            Text(label)
        }
    }
}

private fun favoriteButtonLabel(pending: Boolean, favorite: Boolean): String = when {
    pending -> "处理中"
    favorite -> "已收藏"
    else -> "收藏短剧"
}
