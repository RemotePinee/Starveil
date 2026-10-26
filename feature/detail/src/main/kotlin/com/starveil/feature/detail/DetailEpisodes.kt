package com.starveil.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.starveil.core.designsystem.component.PlayingBars
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.EpisodeText
import com.starveil.core.model.PlayUrl
import com.starveil.core.model.episodeText
import com.starveil.core.designsystem.interaction.rememberHaptics
import com.starveil.core.designsystem.interaction.HapticKind

/**
 * 选集宫格的一行（优酷式数字格）。
 *
 * 旧页用横向 RecyclerView + 自定义适配器，
 * 同样按数组下标比较当前集；每行固定 [EPISODE_COLUMNS] 个，交给外层
 * LazyColumn 逐行懒加载，集数再多也不会一次性 compose。
 * 行内间距与行间距都收到 4dp / 2dp 一档，30 集能在首屏铺满而不碎。
 */
@Composable
internal fun DetailEpisodeRow(
    episodes: List<PlayUrl>,
    startIndex: Int,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    // 一批里只要有一条是文本型标题（日期/版本名），整批改两列宽卡：
    // 六列窄格里塞「2026-10-24」，只会被截成没有区分度的残片。
    // 单集不走宽卡：weight 会让它撑满半屏，看着比标题还大。
    val labels = episodes.mapIndexed { _, episode -> episodeText(episode.title, episode.episodeNumber) }
    if (labels.any { it.wide } && episodes.size > 1) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.xxs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            labels.indices.chunked(WIDE_EPISODE_COLUMNS).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    pair.forEach { column ->
                        val index = startIndex + column
                        WideEpisodeTile(
                            label = labels[column],
                            playing = index == selectedIndex,
                            onClick = { onSelect(index) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(WIDE_EPISODE_COLUMNS - pair.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        episodes.forEachIndexed { column, _ ->
            val index = startIndex + column
            EpisodeChip(
                label = labels[column].primary,
                playing = index == selectedIndex,
                onClick = { onSelect(index) },
                modifier = Modifier.weight(1f),
            )
        }
        repeat(EPISODE_COLUMNS - episodes.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

/** 两行宽卡：主行标题、副行集号/日期。文本型标签用它，信息不丢也不挤。 */
@Composable
private fun WideEpisodeTile(
    label: EpisodeText,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    Column(
        modifier = modifier
            .height(EpisodeWideHeight)
            .clip(AppShape.small)
            .background(if (playing) MaterialTheme.colorScheme.primary else starveilExtraColors.surfaceCard)
            .clickable {
                haptics(HapticKind.Confirm)
                onClick()
            }
            .padding(horizontal = Spacing.xs),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label.primary,
            style = MaterialTheme.typography.bodyMedium,
            color = if (playing) MaterialTheme.colorScheme.onPrimary else starveilExtraColors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (label.secondary.isNotBlank()) {
            Text(
                text = label.secondary,
                style = MaterialTheme.typography.labelSmall,
                color = if (playing) MaterialTheme.colorScheme.onPrimary else starveilExtraColors.textMuted,
                maxLines = 1,
            )
        }
    }
}

/**
 * 数字格：正在播的那一格主色呼吸（强度走 [rememberBreathingAlpha]），其余
 * [com.starveil.core.designsystem.StarveilExtraColors.surfaceCard] 底。
 *
 * 固定 [EpisodeChipHeight] 而不是靠文字内边距撑：一位数与两位数集号高度一致，
 * 宫格才是齐的；8dp 圆角走 [AppShape.small]，不写裸 dp。
 */
@Composable
private fun EpisodeChip(
    label: String,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val background = if (playing) {
        MaterialTheme.colorScheme.primary
    } else {
        starveilExtraColors.surfaceCard
    }
    Box(
        modifier = modifier
            .height(EpisodeChipHeight)
            .clip(AppShape.small)
            .background(background)
            .clickable {
                // 切集是改状态：给一次确认触感
                haptics(HapticKind.Confirm)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (playing) {
            // 正在播放：只放音量柱，集号让位——一排里两种信息会更乱（优酷同款）
            PlayingBars(contentDescription = "正在播放")
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = starveilExtraColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.xxs),
            )
        }
    }
}

/**
 * 「展开全部 / 收起」。
 *
 * 折叠阈值是 [EPISODE_COLLAPSE_LIMIT]（30 集 = 6 列 5 行），超过才出现这一行；
 * 集数本身没变，只是渲染量回到首屏可控的范围，滚动位置不会跳。
 */
@Composable
internal fun DetailEpisodeExpandToggle(
    hiddenCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Text(
        text = if (expanded) "收起" else "展开全部（还有 $hiddenCount 集）",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.sm),
    )
}
/**
 * 一行 6 个（§18.3）。
 *
 * 旧页是横向单行滚动，长剧要一直往右拖；6 列宫格在 360dp 宽屏上每格约 44dp，
 * 三位数集号也放得下，首屏 5 行就能摆下 30 集。
 */
internal const val EPISODE_COLUMNS = 6

/** 数字格高度 36dp：够手指点，又比常规按钮矮一档，5 行 30 集才不占满首屏。 */
private val EpisodeChipHeight: Dp = Spacing.xxl + Spacing.xs

/** 文本型标签用两列宽卡（一行两块），放得下「2026-10-24」「国语原声版」这类标题。 */
private const val WIDE_EPISODE_COLUMNS = 2

/** 宽卡高度 56dp = 两行文字 + 内边距。 */
private val EpisodeWideHeight: Dp = Spacing.xxl + Spacing.xxl
