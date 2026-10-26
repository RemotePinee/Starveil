package com.starveil.feature.pay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.PaymentMethod

/**
 * 支付方式：横向胶囊，选中主色。
 *
 * 渠道码原样回传（[PaymentMethod.code]），本地不枚举渠道，后端加渠道不用发版。
 * 没有可用支付方式时只给一句引导（[EMPTY_ONLINE_HINT] / [EMPTY_EPISODE_HINT]）：
 * 会员模式下卡密兑换仍在页面上，所以引导要指路到卡密，而不是把人卡在"无可用支付方式"。
 */
@Composable
internal fun PayPaymentSection(
    state: PayUiState,
    onSelect: (String) -> Unit,
) {
    PaySectionCard(title = SECTION_TITLE) {
        when {
            state.payments.isEmpty() -> Text(
                text = if (state.isEpisodeMode) EMPTY_EPISODE_HINT else EMPTY_ONLINE_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = starveilExtraColors.textMuted,
            )

            else -> MethodRow(
                methods = state.payments,
                selectedCode = state.selectedPaymentCode,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun MethodRow(
    methods: List<PaymentMethod>,
    selectedCode: String?,
    onSelect: (String) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        items(items = methods, key = { method -> method.code }) { method ->
            MethodPill(
                method = method,
                selected = method.code == selectedCode,
                onClick = { onSelect(method.code) },
            )
        }
    }
}

@Composable
private fun MethodPill(
    method: PaymentMethod,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val pill = Modifier
        .clip(AppShape.full)
        .background(
            if (selected) MaterialTheme.colorScheme.primaryContainer else starveilExtraColors.chipBackground,
        )
        .border(
            width = Spacing.xxs,
            color = if (selected) accent else starveilExtraColors.divider,
            shape = AppShape.full,
        )
        .clickable(onClick = onClick)
        .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
    Text(
        text = method.name,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = if (selected) accent else starveilExtraColors.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = pill,
    )
}

private const val SECTION_TITLE = "支付方式"
private const val EMPTY_ONLINE_HINT = "暂无可用的在线支付方式，可使用下方卡密开通会员"
private const val EMPTY_EPISODE_HINT = "暂无可用的支付方式，请稍后再试"
