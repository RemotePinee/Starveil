package com.starveil.feature.player

import com.starveil.core.model.Vod
/** 预览与剧内共用播放器；导航、收藏、评论与分享的真实实现由应用壳提供。 */
import androidx.compose.ui.unit.Dp
import com.starveil.core.designsystem.token.Spacing
data class VerticalPlayOptions(
    val preview: Boolean = false,
    val active: Boolean = true,
    val onOpenSeries: (Int, Long) -> Unit = { _, _ -> },
    val onAction: (String) -> Unit = {},
    val isFavorite: Boolean = false,
    val pendingAction: String? = null,
    val recommendations: List<Vod> = emptyList(),
    val recommendationsLoading: Boolean = false,
    val recommendationsError: String? = null,
    val onRecommendationClick: (Int) -> Unit = {},
    val onRecommendationsRetry: () -> Unit = {},
    val onRecommendationsOpen: () -> Unit = {},
    /** 应用壳提供的单一展开进度，只驱动局部前景，不改变视频输出身份。 */
    val seriesExpansion: Float = if (preview) 0f else 1f,
    val transitioning: Boolean = false,
    /** 与底栏同一固定占位高度；用于让剧内入口条同步移入 dock 区域。 */
    val seriesDockOffset: Dp = Spacing.none,
    /** 查询中与查询失败必须分开；失败使用重试出口，不继续显示加载态。 */
    val accessPending: Boolean = false,
)
