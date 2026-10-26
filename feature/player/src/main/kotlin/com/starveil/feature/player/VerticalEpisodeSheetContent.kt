package com.starveil.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CaretUp
import com.adamglin.phosphoricons.regular.FilmSlate
import coil3.compose.AsyncImage
import com.starveil.core.designsystem.component.PlayingBars
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing

/**
 * 剧头：封面、剧名、总集数、分类全部来自后端真实字段。
 * 没有热度、评分、演员这类现编信息——选集面板只需要让人确认"点的是哪部剧"。
 */
@Composable
internal fun EpisodeSheetSeriesHeader(state: VerticalPlayUiState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeriesCover(state.cover)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                text = state.title.ifBlank { "短剧" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "全${state.episodes.size}集",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.category.isNotBlank()) {
                Text(
                    text = state.category,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clip(AppShape.small)
                        .background(starveilExtraColors.chipBackground)
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            }
        }
    }
}

@Composable
private fun SeriesCover(cover: String?) {
    Box(
        modifier = Modifier.width(Spacing.xxl * 2).aspectRatio(2f / 3f)
            .clip(AppShape.medium)
            .background(starveilExtraColors.surfaceCard),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = PhosphorIcons.Regular.FilmSlate,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconSize.lg),
        )
        if (!cover.isNullOrBlank()) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** 简介默认两行，展开后随内容长高；滚动容器负责把它推上去，不挤走集数。 */
@Composable
internal fun EpisodeSheetDescription(state: VerticalPlayUiState, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(state.title, state.description) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = state.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.rowHeight).clip(AppShape.small)
                .semantics { stateDescription = if (expanded) "简介已展开" else "简介已收起" }
                .verticalPress(onClick = { expanded = !expanded }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expanded) "收起简介" else "展开简介",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Icon(
                imageVector = if (expanded) {
                    PhosphorIcons.Regular.CaretUp
                } else {
                    PhosphorIcons.Regular.CaretDown
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(IconSize.sm),
            )
        }
    }
}

/** 一组集数：6 列 30 格。缺的行补空位，列宽才不会在最后一格上跳一下。 */
@Composable
internal fun EpisodeRangePage(
    episodes: List<VerticalEpisode>,
    page: Int,
    stage: VerticalPlayStage,
    modifier: Modifier = Modifier,
) {
    val pageEpisodes = episodePage(episodes, page)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(EPISODE_CELL_GAP)) {
        pageEpisodes.chunked(EPISODE_ROW_COLUMNS).forEach { rowEpisodes ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(EPISODE_CELL_GAP),
            ) {
                rowEpisodes.forEach { episode ->
                    EpisodeCell(episode, stage, Modifier.weight(1f))
                }
                repeat(EPISODE_ROW_COLUMNS - rowEpisodes.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** 当前集用强调色实心 + 音量柱；正在播放才有音量柱，暂停时只保留"当前"底色。 */
@Composable
private fun EpisodeCell(episode: VerticalEpisode, stage: VerticalPlayStage, modifier: Modifier) {
    val current = episode.index == stage.state.currentIndex
    val playing = current && stage.state.isPlaying && stage.options.active
    val content = if (current) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier.height(EPISODE_CELL_HEIGHT).clip(AppShape.small)
            .background(
                if (current) MaterialTheme.colorScheme.primaryContainer else starveilExtraColors.surfaceCard,
            )
            .semantics {
                selected = current
                stateDescription = if (playing) "正在播放第${episode.number}集" else "第${episode.number}集"
            }
            .verticalPress(onClick = { stage.actions.onEpisodeSelect(episode.index) })
            .padding(horizontal = Spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (playing) PlayingBars(color = content)
        Text(
            text = episode.number.toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = content,
            maxLines = 1,
        )
    }
}
