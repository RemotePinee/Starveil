package com.starveil.feature.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.designsystem.token.Spacing

/** 详情页的两个内容 Tab：视频（简介/源/选集/推荐）与讨论（评论）。「看点」没有数据源，砍掉。 */
internal enum class DetailTab(val label: String) {
    Video("视频"),
    Comments("讨论"),
}

/** Tab 选中项与切换回调；收成对象以避开 Composable 的参数上限。 */
@Immutable
internal data class DetailTabState(
    val selected: DetailTab,
    val onSelect: (DetailTab) -> Unit,
)

/** 两个 Tab：选中项加粗 + 主色，点击切换下方内容。 */
@Composable
internal fun DetailTabs(
    selected: DetailTab,
    commentCount: Int,
    onSelect: (DetailTab) -> Unit,
    /**
     * 后台「详情页 → 评论」开着才显示讨论 Tab。
     *
     * 关掉时不是把它压灰，而是整块消失：留着点不动比没有更让人困惑。
     */
    showComments: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.pageHorizontal),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        DetailTab.entries
            .filter { showComments || it != DetailTab.Comments }
            .forEach { tab ->
                TabLabel(
                    text = if (tab == DetailTab.Comments) "${tab.label} $commentCount" else tab.label,
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                )
            }
    }
}

/**
 * 单个 Tab：颜色与字重过渡 + 下方主色短条，选中时短条从 0 长到满宽。
 *
 * 只在两处做动画（颜色 120ms、短条 200ms），不做位移/缩放，切起来软但屏幕上不闹。
 */
@Composable
private fun TabLabel(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSurface else starveilExtraColors.textMuted,
        animationSpec = tween(durationMillis = Motion.ShortMs, easing = Motion.standard),
        label = "tabColor",
    )
    val underline by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = Motion.MediumMs, easing = Motion.standard),
        label = "tabUnderline",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(top = Spacing.sm),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = color,
            maxLines = 1,
        )
        Spacer(Modifier.height(Spacing.xs))
        Box(
            modifier = Modifier
                .height(TabIndicatorHeight)
                .width(TabIndicatorWidth * underline)
                .clip(AppShape.full)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = underline)),
        )
    }
}

/** Tab 下的短条：24 × 2dp，宽度随选中状态增长。 */
private val TabIndicatorWidth: Dp = Spacing.xl

private val TabIndicatorHeight: Dp = Spacing.xxs
