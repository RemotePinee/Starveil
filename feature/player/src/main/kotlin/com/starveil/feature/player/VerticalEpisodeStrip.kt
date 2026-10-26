package com.starveil.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing

/**
 * 底部集数条：横向滚动，当前集高亮（计划 §5 P0：高 `EPISODE_STRIP_HEIGHT`）。
 *
 * 用 LazyRow 而不是 `Row + horizontalScroll`：80 集短剧一次性测量全部 chip
 * 会拖慢每帧，Lazy 只组合可见的那几个。点击只上报下标，
 * pager 滚动由页面做——本组件不持有 pager 状态。
 */
@Composable
internal fun VerticalEpisodeStrip(
    episodes: List<VerticalEpisode>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .height(EPISODE_STRIP_HEIGHT),
        contentPadding = PaddingValues(horizontal = Spacing.pageHorizontal),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(items = episodes, key = { it.number }) { episode ->
            EpisodeChip(
                number = episode.number,
                selected = episode.index == currentIndex,
                onClick = { onSelect(episode.index) },
            )
        }
    }
}

/** 单个集数 chip：当前集用强调色实心，其余用 chip 底色。 */
@Composable
private fun EpisodeChip(number: Int, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        starveilExtraColors.chipBackground
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        starveilExtraColors.textMuted
    }
    Box(
        modifier = Modifier
            .clip(AppShape.small)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = content,
        )
    }
}
