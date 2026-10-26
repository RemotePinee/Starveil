package com.starveil.feature.detail

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.ModalBottomSheet
import com.starveil.core.designsystem.token.AppShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.Download
import com.adamglin.phosphoricons.regular.Heart
import com.adamglin.phosphoricons.regular.ShareNetwork
import com.adamglin.phosphoricons.regular.ThumbsUp
import com.starveil.core.designsystem.mirrorIconInRtl
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.model.PlayUrl
import com.starveil.core.model.EpisodeText
import com.starveil.core.model.episodeText
import com.starveil.core.designsystem.component.PlayingBars
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import androidx.compose.ui.unit.Dp
import com.starveil.core.designsystem.token.Motion
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import com.starveil.core.designsystem.interaction.rememberHaptics
import com.starveil.core.designsystem.interaction.HapticKind

/** 操作行：收藏 / 分享 / 下载 / 点赞，对应优酷标题下那排图标。 */
@Composable
internal fun DetailActionRow(favorited: Boolean, onToggleFavorite: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ActionItem(
            icon = if (favorited) PhosphorIcons.Fill.Heart else PhosphorIcons.Regular.Heart,
            label = "收藏",
            active = favorited,
            onClick = onToggleFavorite,
        )
        ActionItem(PhosphorIcons.Regular.ShareNetwork, "分享") {
            Toast.makeText(context, "分享功能即将上线", Toast.LENGTH_SHORT).show()
        }
        ActionItem(PhosphorIcons.Regular.Download, "下载") {
            Toast.makeText(context, "暂不支持下载", Toast.LENGTH_SHORT).show()
        }
        ActionItem(PhosphorIcons.Regular.ThumbsUp, "点赞") {
            Toast.makeText(context, "点赞功能即将上线", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun ActionItem(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) MaterialTheme.colorScheme.primary else starveilExtraColors.textMuted,
            modifier = Modifier.size(IconSize.lg),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = starveilExtraColors.textFaint,
        )
    }
}

/** 选集标题：标题 + 共 N 集，右侧留出展开提示的位置。 */
@Composable
internal fun DetailEpisodeHeader(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "选集",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "  共 $count 集",
            style = MaterialTheme.typography.bodySmall,
            color = starveilExtraColors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 选集：默认横向单行、右侧溢出可滑；点标题（含"共 N 集"与箭头）弹出优酷式选集面板。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun DetailEpisodeParts(
    episodes: List<PlayUrl>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    if (episodes.isEmpty()) return
    var showPicker by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        EpisodeHeader(
            count = episodes.size,
            onOpen = { showPicker = true },
        )
        EpisodeStrip(
            episodes = episodes,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
        )
    }
    if (showPicker) {
        EpisodePickerSheet(
            episodes = episodes,
            selectedIndex = selectedIndex,
            // 先关层再切集：切集要重新起播，别让弹层卡在动画里
            onSelect = { index ->
                showPicker = false
                onSelect(index)
            },
            onDismiss = { showPicker = false },
        )
    }
}

/** 选集标题行：整行可点，但不画水波纹底色——一层灰底横贯整条会像"整块被选中"。 */
@Composable
private fun EpisodeHeader(count: Int, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpen,
            )
            .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "选集",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "  共 $count 集",
            style = MaterialTheme.typography.bodySmall,
            color = starveilExtraColors.textMuted,
        )
        Icon(
            imageVector = PhosphorIcons.Regular.CaretRight,
            contentDescription = "展开选集",
            tint = starveilExtraColors.textMuted,
            modifier = Modifier.size(IconSize.item).mirrorIconInRtl(),
        )
    }
}

/** 选集横条：锚定到正在播的那一集，长剧不该永远停在"第1集 第2集"。 */
@Composable
private fun EpisodeStrip(
    episodes: List<PlayUrl>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val stripState = rememberLazyListState(
        initialFirstVisibleItemIndex = (selectedIndex - STRIP_ANCHOR_OFFSET).coerceAtLeast(0),
    )
    LaunchedEffect(selectedIndex) {
        stripState.animateScrollToItem((selectedIndex - STRIP_ANCHOR_OFFSET).coerceAtLeast(0))
    }
    // 文本型标题（日期/版本名）走宽一点的卡：两行显示，不再截成「2026-10-」残片
    // 单集（电影正片）不走宽卡：一块 96×64 的空格子看着像没渲染完。
    // 只有多数是文本标题时才整条切换：「先导」混在数字集里就一项宽、其余窄，
    // 卡片宽窄不一，横向扫过去参差不齐。
    val labels = episodes.map { episodeText(it.title, it.episodeNumber) }
    val wide = labels.count { it.wide } * 2 > episodes.size && episodes.size > 1
    LazyRow(
        state = stripState,
        contentPadding = PaddingValues(horizontal = Spacing.pageHorizontal),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        itemsIndexed(episodes) { index, _ ->
            EpisodeCell(
                label = labels[index],
                wide = wide,
                playing = index == selectedIndex,
                onClick = { onSelect(index) },
            )
        }
    }
}

/** 选集面板：标题 + 每 50 集一个区间 + 6 列网格（优酷同款）。关闭靠拖拽/点外部/返回，不留 X。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EpisodePickerSheet(
    episodes: List<PlayUrl>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val ranges = remember(episodes.size) { episodes.indices.chunked(RANGE_SIZE) }
    val selectedRange = episodes.indexOfFirst { it.episodeNumber == episodes.getOrNull(selectedIndex)?.episodeNumber }
        .coerceAtLeast(0) / RANGE_SIZE
    var range by rememberSaveable(episodes.size) { mutableIntStateOf(selectedRange) }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = starveilExtraColors.surfaceCard,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "选集",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 区间数量随集数增长（638 集 = 13 个区间），横排挤不下会把「251-300」压成三行
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {

                ranges.forEachIndexed { index, indices ->
                    RangeChip(
                        label = rangeLabel(indices),
                        selected = index == range,
                        onClick = { range = index },
                    )
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = "${episodes.size}集全",
                style = MaterialTheme.typography.bodySmall,
                color = starveilExtraColors.textMuted,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            )
            // 传绝对起始下标：区间切出来的 chunk 不在 0 起，用 /EPISODE_COLUMNS 反推会错位
            ranges[range].chunked(EPISODE_COLUMNS).forEach { chunk ->
                DetailEpisodeRow(
                    episodes = chunk.map { episodes[it] },
                    startIndex = chunk.first(),
                    selectedIndex = selectedIndex,
                    onSelect = onSelect,
                )
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

@Composable
private fun RangeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        softWrap = false,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else starveilExtraColors.textMuted,
        modifier = Modifier
            .clip(AppShape.full)
            .background(if (selected) MaterialTheme.colorScheme.primary else starveilExtraColors.chipBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
    )
}

private fun rangeLabel(indices: List<Int>): String =
    if (indices.isEmpty()) "" else "${indices.first() + 1}-${indices.last() + 1}"

private const val RANGE_SIZE = 50

@Composable
private fun EpisodeCell(
    label: EpisodeText,
    playing: Boolean,
    onClick: () -> Unit,
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
            .size(
                width = if (wide) WIDE_CELL_WIDTH else CELL_WIDTH,
                height = if (wide) WIDE_CELL_HEIGHT else CELL_HEIGHT,
            )
            .clip(AppShape.small)
            .background(background)
            .clickable {
                haptics(HapticKind.Confirm)
                onClick()
            }
            .padding(horizontal = Spacing.xxs),
        contentAlignment = Alignment.Center,
    ) {
        if (playing) {
            // 只放音量柱，集号隐藏
            PlayingBars(contentDescription = "正在播放")
        } else if (wide) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = label.primary,
                    style = MaterialTheme.typography.labelMedium,
                    color = starveilExtraColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (label.secondary.isNotBlank()) {
                    Text(
                        text = label.secondary,
                        style = MaterialTheme.typography.labelSmall,
                        color = starveilExtraColors.textMuted,
                        maxLines = 1,
                    )
                }
            }
        } else {
            Text(
                text = label.primary,
                style = MaterialTheme.typography.labelMedium,
                color = starveilExtraColors.textMuted,
                maxLines = 1,
            )
        }
    }
}

private val CELL_WIDTH: Dp = Spacing.xxl + Spacing.lg
private val CELL_HEIGHT: Dp = Spacing.xxl + Spacing.xs

/** 文本型标题的宽卡：一行放得下「2026-10-24」，两行放得下标题 + 集号。 */
private val WIDE_CELL_WIDTH: Dp = Spacing.xxl * 3
private val WIDE_CELL_HEIGHT: Dp = Spacing.xxl + Spacing.xxl

/** 横条锚定时往上留两格，当前集不会贴在最左边。 */
private const val STRIP_ANCHOR_OFFSET = 2
