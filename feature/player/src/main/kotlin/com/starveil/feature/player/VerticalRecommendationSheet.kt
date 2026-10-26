package com.starveil.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.FilmSlate
import com.adamglin.phosphoricons.regular.X
import coil3.compose.AsyncImage
import com.starveil.core.designsystem.component.StarveilLoading
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.Vod

/** 仅渲染应用壳传入的真实推荐；请求、重试和导航均通过 options 上抛。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VerticalRecommendationSheet(stage: VerticalPlayStage) {
    ModalBottomSheet(
        onDismissRequest = stage.actions.onToggleRecommendations,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = AppShape.sheetTop,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = { RecommendationHandle() },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.75f)) {
            RecommendationHeader(stage.actions.onToggleRecommendations)
            RecommendationGrid(stage.options, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RecommendationHandle() {
    Box(Modifier.fillMaxWidth().padding(vertical = Spacing.md), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(Spacing.xxl, Spacing.xs).clip(AppShape.full)
                .background(starveilExtraColors.divider),
        )
    }
}

@Composable
private fun RecommendationHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "相关推荐", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f),
        )
        Icon(
            PhosphorIcons.Regular.X, contentDescription = "关闭相关推荐",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(Spacing.rowHeight).clip(AppShape.full)
                .verticalPress(onClick = onDismiss).padding(Spacing.lg),
        )
    }
}

/** 更新或重试失败时仍保留已有推荐，不用整页错误态覆盖真实内容。 */
@Composable
private fun RecommendationGrid(options: VerticalPlayOptions, modifier: Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        when {
            options.recommendationsLoading -> item(span = { GridItemSpan(maxLineSpan) }) {
                StarveilLoading(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
                    message = "正在加载相关推荐",
                )
            }
            options.recommendationsError != null -> item(span = { GridItemSpan(maxLineSpan) }) {
                RecommendationStatus(options.recommendationsError, options.onRecommendationsRetry)
            }
            options.recommendations.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                RecommendationStatus("暂无相关推荐", options.onRecommendationsRetry)
            }
        }
        items(options.recommendations, key = { it.id }) { vod ->
            RecommendationPoster(vod, options.onRecommendationClick)
        }
    }
}

@Composable
private fun RecommendationStatus(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onRetry) { Text("重新加载") }
    }
}

@Composable
private fun RecommendationPoster(vod: Vod, onClick: (Int) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(AppShape.medium).verticalPress(onClick = { onClick(vod.id) }),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(AppShape.medium)
                .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                PhosphorIcons.Regular.FilmSlate, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(IconSize.lg),
            )
            if (!vod.poster.isNullOrBlank()) {
                AsyncImage(
                    model = vod.poster, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        Text(
            text = vod.name, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}
