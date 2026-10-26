package com.starveil.feature.pay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.PayPlan

/**
 * 套餐区：横向滑动的卡片，一屏能看到两张以上。
 *
 * 历史版本是整页竖排的三张单选行（`RadioButton` + 价格在右），一屏只放得下套餐，支付方式要往下滚
 * 才看得见。这里改成卡片：[PayPlan.period] 小字在上、[PayPlan.priceYuan] 大字在下、
 * 日均价垫底，选中用主色描边 + `primaryContainer` 底，不再依赖 RadioButton。
 *
 * 卡片宽度固定（[PLAN_CARD_WIDTH]），套餐数量由后端下发（日/周/月/年），横滑比写死两列更稳。
 */
@Composable
internal fun PayPlanSection(state: PayUiState, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        PaySectionTitle(text = SECTION_TITLE)
        // 每行最多 3 张，等宽铺满；套餐再多就换行，绝不横向溢出
        state.plans.chunked(PLANS_PER_ROW).forEach { rowPlans ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                rowPlans.forEach { plan ->
                    PlanCard(
                        plan = plan,
                        selected = plan.id == state.selectedPlanId,
                        onClick = { onSelect(plan.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(PLANS_PER_ROW - rowPlans.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PlanCard(
    plan: PayPlan,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        starveilExtraColors.surfaceCard
    }
    val borderColor = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier
            .clip(AppShape.medium)
            .background(container)
            .then(
                if (selected) {
                    Modifier.border(width = Spacing.xxs, color = borderColor, shape = AppShape.medium)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Text(
            text = plan.period.title,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                starveilExtraColors.textMuted
            },
            maxLines = 1,
        )
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            Text(
                text = CURRENCY,
                style = MaterialTheme.typography.labelMedium,
                color = starveilExtraColors.brandGold,
            )
            Text(
                text = plan.priceYuan.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = starveilExtraColors.brandGold,
                maxLines = 1,
            )
        }
        Text(
            text = plan.dailyPrice,
            style = MaterialTheme.typography.labelSmall,
            color = starveilExtraColors.textFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val SECTION_TITLE = "选择套餐"
private const val CURRENCY = "¥"

private const val PLANS_PER_ROW = 3
