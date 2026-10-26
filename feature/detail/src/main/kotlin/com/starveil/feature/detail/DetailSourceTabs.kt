package com.starveil.feature.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import com.starveil.core.designsystem.component.SectionHeader
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.PlaySource

/**
 * 播放源标签（旧页 TabLayout）。
 *
 * 源是"线路"概念，不是清晰度：选中只换选集列表，不重新拉详情。
 * 指示条是一次 [Motion.MediumMs]（200ms）的淡入淡出，选中项文字颜色同步渐变，
 * 与排行榜分段控件同一套手感（`RankPeriodTabs.kt`）。
 */
@Composable
internal fun DetailSourceTabs(
    sources: List<PlaySource>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    if (sources.isEmpty()) return
    Column(modifier = Modifier.padding(top = Spacing.sm)) {
        SectionHeader(title = "播放源")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            sources.forEachIndexed { index, source ->
                SourceTab(
                    name = source.name.ifBlank { DEFAULT_SOURCE_NAME },
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                )
            }
        }
    }
}

/** 单个源：文字 + 下方 2dp 指示条。指示条用 drawBehind 画，宽度天然等于文字宽。 */
@Composable
private fun SourceTab(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val emphasis by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = Motion.medium,
        label = "detailSourceIndicator",
    )
    val idle = starveilExtraColors.textMuted
    val active = MaterialTheme.colorScheme.onSurface
    val indicator = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            // 去掉默认 ripple：深色页上点击会出现一块半透明方框，和文字下划线重复
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = Spacing.sm),
    ) {
        Column(
            modifier = Modifier.padding(bottom = INDICATOR_HEIGHT + Spacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                color = lerp(idle, active, emphasis.coerceIn(0f, 1f)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    val bar = INDICATOR_HEIGHT.toPx()
                    drawRect(
                        color = indicator.copy(alpha = emphasis.coerceIn(0f, 1f)),
                        topLeft = Offset(0f, size.height - bar),
                        size = Size(size.width, bar),
                    )
                },
        )
    }
}

/** 源名可能为空，此时显示「默认」（旧页 `newTab()` 同样兜底）。 */
private const val DEFAULT_SOURCE_NAME = "默认"

/** 指示条高度：2dp，来自间距令牌。 */
private val INDICATOR_HEIGHT = Spacing.xxs

