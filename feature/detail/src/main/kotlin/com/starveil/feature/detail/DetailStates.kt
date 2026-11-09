package com.starveil.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.starveil.core.designsystem.token.Spacing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import com.starveil.core.designsystem.component.SkeletonBlock

/** 空态与错误态的居中占位，保留一个可点的出口。 */
@Composable
internal fun DetailStateMessage(
    title: String,
    detail: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAction) { Text(text = actionLabel) }
    }
}

/**
 * 加载态骨架：和 Content 的首屏同构——顶部 16:9 头图占位，下面标题 / 元信息 / 简介骨架。
 *
 * 之前这里用整屏居中的 [com.starveil.core.designsystem.component.StarveilLoadingScreen]：
 * 数据一到，内容从屏幕顶部铺开，视觉重心从中间跳到顶部，看起来就是"进来先位移一下"。
 * 头图占位与真头图同高（[HERO_ASPECT]），Content 出来后页面上沿一根线都不动。
 */
@Composable
internal fun DetailSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(HERO_ASPECT)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            SkeletonBlock(Modifier.fillMaxWidth(TITLE_WIDTH).height(Spacing.xl))
            SkeletonBlock(Modifier.fillMaxWidth(META_WIDTH).height(Spacing.md))
            SkeletonBlock(Modifier.fillMaxWidth(TAB_WIDTH).height(Spacing.md))
            repeat(SKELETON_LINES) { index ->
                val fraction = if (index == SKELETON_LINES - 1) LAST_LINE_WIDTH else 1f
                SkeletonBlock(Modifier.fillMaxWidth(fraction).height(Spacing.md))
            }
        }
    }
}

/** 骨架各行的宽度比例：照真实首屏的行长走，别画成一个天平。 */
private const val TITLE_WIDTH = 0.55f
private const val META_WIDTH = 0.85f
private const val TAB_WIDTH = 0.30f
private const val LAST_LINE_WIDTH = 0.60f
private const val SKELETON_LINES = 3
