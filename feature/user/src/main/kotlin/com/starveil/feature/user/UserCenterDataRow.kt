package com.starveil.feature.user

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.starveil.core.designsystem.token.Spacing

/**
 * 数值行：积分 / 金币 / 观看时长，三格等分，直接落在资料带上。
 *
 * 这一行原来单独套一张卡片，与头像卡是"两张卡"；参考布局里它们属于同一块区域，
 * 所以这里只留数值和标签，不再画底色和圆角。
 *
 * 五格会把数字挤小，所以只保留三项最常看的；历史与卡密在下面的快捷入口和常用工具里。
 *
 * 数值只来自 [UserCenterMetrics]：接口没给时恒为占位符（"——"），
 * 不拿本地列表条数冒充；未登录时同样落到占位符。
 */
@Composable
internal fun UserCenterDataRow(
    metrics: UserCenterMetrics,
    onEntryClick: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        MetricCell(metrics.points, LABEL_POINTS, Modifier.weight(WEIGHT_FULL)) {
            onEntryClick(ENTRY_POINTS)
        }
        MetricCell(metrics.gold, LABEL_GOLD, Modifier.weight(WEIGHT_FULL)) {
            onEntryClick(ENTRY_GOLD)
        }
        MetricCell(metrics.watch, LABEL_WATCH, Modifier.weight(WEIGHT_FULL))
    }
}

/** 一格数字：有入口才可点（观看时长没有对应页面，纯展示）。 */
@Composable
private fun MetricCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            // minLines 保证空串时也占一行，数值到位不会把下面顶下去
            minLines = 1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = LABEL_ALPHA),
            maxLines = 1,
        )
    }
}

private const val WEIGHT_FULL = 1f
private const val LABEL_ALPHA = 0.82f

private const val LABEL_WATCH = "观看时长"
private const val LABEL_POINTS = "积分"
private const val LABEL_GOLD = "金币"
private const val ENTRY_POINTS = "积分中心"
private const val ENTRY_GOLD = "金币提现"
