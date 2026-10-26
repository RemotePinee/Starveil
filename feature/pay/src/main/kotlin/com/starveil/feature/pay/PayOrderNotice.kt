package com.starveil.feature.pay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing

/**
 * 待支付订单区块，显示在套餐与支付方式上方。
 *
 * 对应旧页回跳后的三条提示：未支付、支付成功、
 * 以及兑换会员的结果。用 Toast 一次性弹完就没了，用户切回来晚一秒
 * 就看不到结果；这里把结果常驻在状态里，最多被下一单覆盖。
 *
 * 数据来自 [PayUiState.pendingOrderCode] / [PayUiState.orderStatus] /
 * [PayUiState.lastOrderMessage]，本组件不发请求、不改状态。
 */
@Composable
internal fun PayOrderNotice(state: PayUiState) {
    if (!state.hasOrderNotice) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShape.medium,
        colors = CardDefaults.cardColors(containerColor = starveilExtraColors.surfaceElevated),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = "待支付订单",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OrderCodeLine(state = state)
            OrderStatusLine(state = state)
            OrderMessageLine(state = state)
        }
    }
}

/** 订单号：查单前用待查的编号，查完后用后端回传的编号。 */
@Composable
private fun OrderCodeLine(state: PayUiState) {
    val orderCode = state.pendingOrderCode ?: state.orderStatus?.orderCode
    if (orderCode.isNullOrBlank()) return
    Text(
        text = "订单号 $orderCode",
        style = MaterialTheme.typography.bodySmall,
        color = starveilExtraColors.textMuted,
    )
}

/** 状态行：查询中 / 已支付还是待支付 + 金额。 */
@Composable
private fun OrderStatusLine(state: PayUiState) {
    val status = state.orderStatus
    val text = when {
        state.isCheckingOrder -> "正在确认支付结果…"
        status != null -> "${status.statusText} · ¥${status.priceYuan}"
        else -> null
    }
    if (text == null) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** 结果提示：兑换成功用主色，其余（未支付 / 兑换失败）用次要文字色。 */
@Composable
private fun OrderMessageLine(state: PayUiState) {
    val message = state.lastOrderMessage ?: return
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = if (state.membershipActivated) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}
