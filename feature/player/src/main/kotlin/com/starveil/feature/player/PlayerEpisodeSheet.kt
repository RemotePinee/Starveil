package com.starveil.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.starveil.core.designsystem.component.PlayingBars
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.episodeChipLabel
import com.starveil.core.model.episodeText
import com.starveil.core.designsystem.interaction.rememberHaptics
import com.starveil.core.designsystem.interaction.HapticKind

/** 选集宫格的列数：优酷式 5 列，横屏半屏里也不至于挤成一团。 */
private const val EPISODE_COLUMNS = 5

/** 文本型标题用两列宽卡：一行放得下日期或版本名，不再被截成残片。 */
private const val WIDE_EPISODE_COLUMNS = 2

/** 宽卡高度 60dp = 两行文字 + 内边距。 */
private val WideCellHeight: Dp = Spacing.xxl + Spacing.xxl + Spacing.xs

/** 弹层高度占屏高比例：够看两三行选集，又不把画面整块盖掉。 */
private const val SHEET_HEIGHT_FRACTION = 0.45f

/** 单元 48dp = 32 + 16，圆角 8dp（AppShape.small）。 */
private val CellHeight: Dp = Spacing.xxl + Spacing.lg

/** Tab 行高 44dp：与底部操作行同档。 */
private val TabHeight: Dp = Spacing.xxl + Spacing.md

/** Tab 选中指示条 24 × 2dp。 */
private val TabIndicatorWidth: Dp = Spacing.xl

private val TabIndicatorHeight: Dp = Spacing.xxs

/** 选集弹层的两个 Tab：线路并入选集，操作行上不再单列一个线路图标。 */
private enum class EpisodeTab(val label: String) {
    Episodes("选集"),
    Lines("线路"),
}

/**
 * 选集弹层：Tab（选集 / 线路）→ 可滚动的宫格或线路列表。
 *
 * 宫格打开时锚定到正在播的那一集：162 集的剧如果每次都从第 1 集开始滚，
 * 用户要点十几次才能翻到当前集。切集/换线路都是"先关层再动手"——
 * 换集会触发重新解析，弹层没必要卡在动画里。
 */
@Composable
internal fun EpisodeSheet(state: PlayerUiState, actions: PlayerActions, onDismiss: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(EpisodeTab.Episodes) }
    val height = (LocalConfiguration.current.screenHeightDp * SHEET_HEIGHT_FRACTION).dp
    Column(modifier = Modifier.fillMaxWidth().height(height)) {
        SheetTabs(selected = tab, onSelect = { tab = it })
        when (tab) {
            EpisodeTab.Episodes -> EpisodeGrid(
                episodes = state.episodes,
                currentIndex = state.episodeIndex,
                onSelect = { index ->
                    onDismiss()
                    actions.onEpisodeSelected(index)
                },
                modifier = Modifier.weight(1f),
            )

            EpisodeTab.Lines -> LineList(
                lines = state.qualities,
                currentSourceIndex = state.sourceIndex,
                onSelect = { sourceIndex ->
                    onDismiss()
                    actions.onSourceSelected(sourceIndex)
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 两个等宽 Tab，选中项加粗并在下方压一条主色短条。 */
@Composable
private fun SheetTabs(selected: EpisodeTab, onSelect: (EpisodeTab) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {
        EpisodeTab.entries.forEach { tab ->
            TabItem(tab = tab, selected = tab == selected, onClick = { onSelect(tab) })
        }
    }
}

@Composable
private fun RowScope.TabItem(tab: EpisodeTab, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.weight(1f).height(TabHeight).clickable(onClick = onClick),
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface else starveilExtraColors.textMuted,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .width(TabIndicatorWidth)
                .height(TabIndicatorHeight)
                .clip(AppShape.full)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
    }
}

/** 选集宫格：5 列、单元 48dp；正在播的那一格主色呼吸，其余 surfaceCard。 */
@Composable
private fun EpisodeGrid(
    episodes: List<String>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (episodes.isEmpty()) {
        EmptyHint(text = "暂无可选集数", modifier = modifier)
        return
    }
    // 打开就停在当前集上一行附近，当前集不会被顶到看不见的地方
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = (currentIndex - EPISODE_COLUMNS).coerceAtLeast(0),
    )
    // 一批里只要有一条是"文本型"标题（日期/版本名），整批改两列宽卡：
    // 四列窄格里塞 `2026-10-24`，只会被截成「2026-10-」这种没有区分度的残片。
    val labels = episodes.mapIndexed { index, title -> episodeText(title, index + 1) }
    val wide = labels.any { it.wide }
    LazyVerticalGrid(
        columns = GridCells.Fixed(if (wide) WIDE_EPISODE_COLUMNS else EPISODE_COLUMNS),
        state = gridState,
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = modifier.fillMaxWidth(),
    ) {
        itemsIndexed(episodes) { index, _ ->
            GridCell(
                label = labels[index].primary,
                secondary = labels[index].secondary,
                wide = wide,
                playing = index == currentIndex,
                onClick = { onSelect(index) },
            )
        }
    }
}

/** 线路 Tab：与选集同一套单元样式（48dp、8dp 圆角、选中主色实底），只排成整行。 */
@Composable
private fun LineList(
    lines: List<QualityOption>,
    currentSourceIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) {
        EmptyHint(text = "暂无可切换线路", modifier = modifier)
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(lines) { option ->
            LineRow(
                label = option.label,
                selected = option.sourceIndex == currentSourceIndex,
                onClick = { onSelect(option.sourceIndex) },
            )
        }
    }
}

/**
 * 选集单元：正在播的那一格用主色实底，其余 surfaceCard。
 *
 * [wide] 为 true 时是两行卡（主行标题、副行集号/日期），高度一起放大；
 * 窄格只放一行，保持原来的密度。
 */
@Composable
private fun GridCell(
    label: String,
    playing: Boolean,
    onClick: () -> Unit,
    secondary: String = "",
    wide: Boolean = false,
) {
    val haptics = rememberHaptics()
    val background = if (playing) {
        MaterialTheme.colorScheme.primary
    } else {
        starveilExtraColors.surfaceCard
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (wide) WideCellHeight else CellHeight)
            .clip(AppShape.small)
            .background(background)
            .clickable {
                haptics(HapticKind.Confirm)
                onClick()
            }
            .padding(horizontal = Spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        if (playing) {
            // 只放音量柱：集号让位，和详情页选集保持一致
            PlayingBars(contentDescription = "正在播放")
        } else if (wide) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (secondary.isNotBlank()) {
                    Text(
                        text = secondary,
                        style = MaterialTheme.typography.labelSmall,
                        color = starveilExtraColors.textMuted,
                        maxLines = 1,
                    )
                }
            }
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LineRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(CellHeight)
            .clip(AppShape.small)
            .background(if (selected) MaterialTheme.colorScheme.primary else starveilExtraColors.surfaceCard)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = starveilExtraColors.textMuted)
    }
}
