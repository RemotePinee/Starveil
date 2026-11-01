package com.starveil.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.CaretUp
import com.adamglin.phosphoricons.regular.PlayCircle
import com.starveil.core.designsystem.mirrorIconInRtl
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing

/** 底条文案集中在此：入口能力与文案一一对应，不在各处重复字面量。 */
private const val LABEL_EPISODES = "选集"
private const val LABEL_RECOMMENDATIONS = "相关推荐"
private const val LABEL_FULL_SERIES = "观看完整短剧"
private const val LABEL_EXPAND = "展开"
private const val STRIP_FADE_PHASES = 2f

/** 进度条叠层：圆点下半要压在入口条之上，靠层级而不是改测量高度。 */
private const val PROGRESS_LAYER = 1f

/**
 * 底部信息与入口区。
 *
 * 版式参照红果：上段是标题 / 分类 / 简介与右栏，下段把进度和入口条压成一整块贴底区域——
 * 进度几乎贴着入口条上沿，入口条整幅贴底、不外加底部留白，两种模式共用随明暗主题变化的底色。
 * 剧内态整组跟着底槽一起下移（进度始终贴着入口条），试看态保持原位，底槽等高。
 */
@Composable
internal fun BoxScope.VerticalBottomChrome(stage: VerticalPlayStage) {
    val expansion = stage.options.seriesExpansion.coerceIn(0f, 1f)
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            // 整组前景（信息区 / 进度 / 入口条）一起下移：进度始终贴着入口条，不空出黑槽。
            .graphicsLayer {
                translationY = stage.options.seriesDockOffset.toPx() * expansion
            }
    ) {
        // 信息区自带左右内边距：贴底区域要整幅铺开，不能被这段 padding 收窄。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = Spacing.pageHorizontal,
                    end = Spacing.xs,
                    top = Spacing.md,
                ),
            verticalAlignment = Alignment.Bottom,
        ) {
            SeriesSummary(stage, Modifier.weight(1f).padding(end = Spacing.md))
            VerticalSideRail(stage)
        }
        // 触摸区与入口条从首帧即占同一位置，不等详情回来再添加。
        // 圆点下半探进入口条顶部，靠层级绘制而不是改轨道中心线。
        ProgressBar(
            state = stage.state,
            onSeek = stage.actions.onSeek,
            modifier = Modifier.zIndex(PROGRESS_LAYER).padding(top = Spacing.xs),
        )
        SeriesBottomActions(stage)
    }
}

/** 底槽等高、整幅贴底：两个模式只替换这一行，标题、右栏与进度位置完全共用。 */
@Composable
private fun SeriesBottomActions(stage: VerticalPlayStage) {
    Box(
        // 位移由上层整组负责：这一条只负责贴底、裁切与底色。
        modifier = Modifier
            .fillMaxWidth()
            .height(Spacing.rowHeight)
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        SeriesStripContents(stage)
    }
}

@Composable
private fun SeriesStripContents(stage: VerticalPlayStage) {
    val expansion = stage.options.seriesExpansion.coerceIn(0f, 1f)
    val enabled = !stage.options.transitioning
    val previewAlpha = previewStripOpacity(expansion)
    val seriesAlpha = seriesStripOpacity(expansion)
    // 文字在各自的水平锚点退出/进入；中点交接，避免左右错位的两行字叠读。
    if (expansion < 1f) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = previewAlpha
                translationY = -Spacing.xs.toPx() * (1f - previewAlpha)
            }.then(if (enabled && stage.options.preview) Modifier else Modifier.clearAndSetSemantics {}),
        ) { FullSeriesStrip(stage) }
    }
    if (expansion > 0f) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = seriesAlpha
                translationY = Spacing.xs.toPx() * (1f - seriesAlpha)
            }.then(if (enabled && !stage.options.preview) Modifier else Modifier.clearAndSetSemantics {}),
        ) { SeriesNavigation(stage) }
    }
}

internal fun previewStripOpacity(expansion: Float): Float =
    (1f - expansion * STRIP_FADE_PHASES).coerceIn(0f, 1f)

internal fun seriesStripOpacity(expansion: Float): Float =
    (expansion * STRIP_FADE_PHASES - 1f).coerceIn(0f, 1f)

/** 标题 / 分类 / 简介紧凑分三层，行间只留 [Spacing.xs]，不再一层一个 48 高槽位。 */
@Composable
private fun SeriesSummary(stage: VerticalPlayStage, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        VerticalPlaybackStatus(stage)
        SeriesTitle(stage)
        if (stage.state.category.isNotBlank()) {
            // 分类是标签而不是分组标题：真正药丸形，底色用既有 chip 令牌，不叠半透明。
            Text(
                text = stage.state.category,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(AppShape.full)
                    .background(starveilExtraColors.chipBackground)
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
            )
        }
        if (stage.state.description.isNotBlank()) {
            SynopsisLine(stage)
        }
    }
}

@Composable
private fun SeriesTitle(stage: VerticalPlayStage) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(AppShape.small)
            .verticalPress(
                enabled = stage.state.episodes.isNotEmpty() && !stage.options.transitioning,
                onClick = stage.actions.onToggleEpisodes,
            )
            .padding(vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = stage.state.title.ifBlank { "短剧" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = starveilExtraColors.onImage,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            PhosphorIcons.Regular.CaretRight, contentDescription = "展开选集",
            tint = starveilExtraColors.onImage,
            modifier = Modifier.size(IconSize.item).mirrorIconInRtl(),
        )
    }
}

/**
 * 简介两行封顶，"展开"并入同一行末尾。
 *
 * 剧数在顶部 `EpisodeTopBar` 已经写过，这里不再重复"第 N 集"，也不造"已看完"之类的假状态。
 */
@Composable
private fun SynopsisLine(stage: VerticalPlayStage) {
    Row(
        modifier = Modifier.fillMaxWidth()
            // 右侧能力栏是叠在画面上的独立一列，这里必须留出它的宽度：
            // 否则描述用 weight 撑满整行，"展开"会被推到图标栏底下，看着像跑到了右栏里
            .padding(end = SIDE_RAIL_WIDTH)
            .clip(AppShape.small)
            .verticalPress(onClick = stage.actions.onToggleEpisodes)
            .padding(vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = stage.state.description,
            style = MaterialTheme.typography.bodySmall,
            color = starveilExtraColors.onImage,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            // fill = false：描述短（或为空）时按自身宽度占位，"展开"紧跟文字；
            // 用 weight(1f) 会把剩余宽度全吃掉，"展开"被推到图标栏边上，看着离标题很远
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = LABEL_EXPAND,
            style = MaterialTheme.typography.labelSmall,
            color = starveilExtraColors.onImage,
            maxLines = 1,
        )
        Icon(
            PhosphorIcons.Regular.CaretDown, contentDescription = null,
            tint = starveilExtraColors.onImage, modifier = Modifier.size(IconSize.sm),
        )
    }
}

/** 试看态入口：整幅贴住底栏，进整剧播放。 */
@Composable
private fun FullSeriesStrip(stage: VerticalPlayStage) {
    val count = stage.state.episodeCount
    Row(
        modifier = Modifier.fillMaxSize()
            .verticalPress(
                onClick = stage.actions.onOpenSeries,
                enabled = stage.state.episodes.isNotEmpty() && stage.options.preview && !stage.options.transitioning,
            )
            .padding(horizontal = Spacing.pageHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            PhosphorIcons.Regular.PlayCircle, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(IconSize.item),
        )
        Text(
            text = if (count > 0) "$LABEL_FULL_SERIES · 全${count}集" else LABEL_FULL_SERIES,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            PhosphorIcons.Regular.CaretRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(IconSize.item).mirrorIconInRtl(),
        )
    }
}

/**
 * 剧内底条：常态只有"选集 · 全 N 集"并撑满整幅；
 * 到最后一集才并排给出相关推荐入口，两段各占一半。
 */
@Composable
private fun SeriesNavigation(stage: VerticalPlayStage) {
    val state = stage.state
    val atLastEpisode = state.episodes.isNotEmpty() && state.currentIndex == state.episodes.lastIndex
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeriesNavigationItem(
            label = episodeLabel(state.episodeCount),
            onClick = stage.actions.onToggleEpisodes,
            enabled = state.episodes.isNotEmpty() && !stage.options.preview && !stage.options.transitioning,
            modifier = if (atLastEpisode) Modifier.weight(1f) else Modifier.fillMaxWidth(),
        )
        if (atLastEpisode) {
            StripDivider()
            SeriesNavigationItem(
                label = LABEL_RECOMMENDATIONS,
                onClick = stage.actions.onToggleRecommendations,
                enabled = !stage.options.preview && !stage.options.transitioning,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun episodeLabel(count: Int): String =
    if (count > 0) "$LABEL_EPISODES · 全${count}集" else LABEL_EPISODES

/** 底条里的一段：整段透明压在贴底背景上，按整块触发，不画悬浮卡片。 */
@Composable
private fun SeriesNavigationItem(label: String, onClick: () -> Unit, modifier: Modifier, enabled: Boolean) {
    Row(
        modifier = modifier.fillMaxHeight()
            .verticalPress(onClick = onClick, enabled = enabled)
            .padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterHorizontally),
    ) {
        Text(
            label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            PhosphorIcons.Regular.CaretUp, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(IconSize.item),
        )
    }
}

/** 两段之间的细分隔线：一条贴底栏被切开，而不是两张并排卡片。 */
@Composable
private fun StripDivider() {
    Spacer(
        Modifier
            .width(Spacing.hairline)
            .height(Spacing.xl)
            .background(starveilExtraColors.divider),
    )
}
