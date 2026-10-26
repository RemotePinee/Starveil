package com.starveil.feature.rank

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.designsystem.token.Spacing
import kotlin.math.abs

/**
 * 日 / 周 / 月分段控件（本模块自绘，不跨 feature 取首页的 Tab 组件）。
 *
 * 只暴露仓库真正支持的三个维度：[RankOrder.DAY]、[RankOrder.WEEK]、[RankOrder.MONTH]
 * （`vod_hits_day` / `vod_hits_week` / `vod_hits_month`）。总榜没有进分段，因为它不在
 * 排行榜页的交互里，避免出现"选了但数据不跟着变"的假按钮。
 *
 * 指示器是一次 [Motion.MediumMs]（200ms）的滑动，选中色随之渐变，而不是硬切换。
 */
@Composable
internal fun RankPeriodTabs(
    selected: RankOrder,
    onSelect: (RankOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    val periods = RANK_PERIODS
    val selectedIndex = periods.indexOf(selected).coerceAtLeast(0)
    val position by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = Motion.medium,
        label = "rankPeriodIndicator",
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppShape.full)
            .background(starveilExtraColors.chipBackground)
            .padding(Spacing.xs),
    ) {
        val slotWidth = maxWidth / periods.size
        Box(
            modifier = Modifier
                .offset(x = slotWidth * position)
                .width(slotWidth)
                .height(TAB_HEIGHT)
                .clip(AppShape.full)
                .background(MaterialTheme.colorScheme.primary),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            periods.forEachIndexed { index, order ->
                RankPeriodTab(
                    label = order.label,
                    // 指示器滑到哪，哪个标签就最亮；滑过中间标签时颜色跟着渐变。
                    emphasis = 1f - abs(position - index),
                    onClick = { onSelect(order) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun RankPeriodTab(
    label: String,
    emphasis: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val idle = starveilExtraColors.textMuted
    val active = MaterialTheme.colorScheme.onPrimary
    Box(
        modifier = modifier
            .height(TAB_HEIGHT)
            .clip(AppShape.full)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (emphasis > EMPHASIS_THRESHOLD) FontWeight.SemiBold else FontWeight.Normal,
            color = lerp(idle, active, emphasis.coerceIn(0f, 1f)),
            textAlign = TextAlign.Center,
        )
    }
}

/** 分段顺序固定为"由短到长"，与商业榜单页的阅读习惯一致。 */
private val RANK_PERIODS = listOf(RankOrder.DAY, RankOrder.WEEK, RankOrder.MONTH)

/** 分段高度：32dp + 4dp，全部来自间距令牌，不写裸 dp。 */
private val TAB_HEIGHT = Spacing.xxl + Spacing.xs

private const val EMPHASIS_THRESHOLD = 0.5f
