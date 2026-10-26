package com.starveil.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.FilmSlate
import coil3.compose.AsyncImage
import com.starveil.core.designsystem.component.StarveilLoading
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.Vod

/** 推荐位 3 列，和独立的相关推荐弹层同一密度。 */
private const val RECOMMENDATION_COLUMNS = 3

/** 面板里只铺两行：再多就把选集挤没了，用户想看全部可以走独立推荐入口。 */
private const val RECOMMENDATION_LIMIT = 6

/**
 * 选集面板最下面的"更多好剧"。数据全部来自 `options.recommendations`，
 * 请求、重试、跳转都通过 options 上抛；本组件不自己发请求，也不编造热度。
 *
 * 加载中沿用全局 [StarveilLoading]；失败保留已有列表，只有一条都没有时才占位。
 */
@Composable
internal fun EpisodeRecommendations(options: VerticalPlayOptions, modifier: Modifier = Modifier) {
    val hasItems = options.recommendations.isNotEmpty()
    val awaitingResult = options.recommendationsLoading || options.recommendationsError != null
    if (!hasItems && !awaitingResult) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            text = "更多好剧",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = Spacing.lg),
        )
        when {
            hasItems -> RecommendationGrid(options.recommendations, options.onRecommendationClick)
            options.recommendationsLoading -> StarveilLoading(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
                message = "正在加载更多好剧",
            )
            else -> RecommendationNotice(
                message = options.recommendationsError ?: "暂无更多好剧",
                onRetry = options.onRecommendationsRetry,
            )
        }
    }
}

@Composable
private fun RecommendationGrid(items: List<Vod>, onSelect: (Int) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        items.take(RECOMMENDATION_LIMIT).chunked(RECOMMENDATION_COLUMNS).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                rowItems.forEach { vod ->
                    RecommendationPoster(vod, onSelect, Modifier.weight(1f))
                }
                repeat(RECOMMENDATION_COLUMNS - rowItems.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RecommendationPoster(vod: Vod, onSelect: (Int) -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier.clip(AppShape.medium).verticalPress(onClick = { onSelect(vod.id) }),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(AppShape.medium)
                .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = PhosphorIcons.Regular.FilmSlate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(IconSize.lg),
            )
            if (!vod.poster.isNullOrBlank()) {
                AsyncImage(
                    model = vod.poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        Text(
            text = vod.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RecommendationNotice(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRetry) { Text("重新加载") }
    }
}
