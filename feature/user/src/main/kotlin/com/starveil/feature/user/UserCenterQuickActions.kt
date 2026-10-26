package com.starveil.feature.user

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.regular.ClockCounterClockwise
import com.adamglin.phosphoricons.regular.Coin
import com.adamglin.phosphoricons.regular.Ticket
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing

/** 一个快捷入口：图标 + 文案 + 上抛的入口名（路由映射仍在 :app）。 */
private data class QuickAction(val icon: ImageVector, val label: String, val entry: String)

/** 四个最常用的入口：收藏、观看记录、卡密兑换、金币提现。 */
private val QUICK_ACTIONS = listOf(
    QuickAction(PhosphorIcons.Fill.Heart, "我的收藏", "我的收藏"),
    QuickAction(PhosphorIcons.Regular.ClockCounterClockwise, "观看记录", "观看记录"),
    QuickAction(PhosphorIcons.Regular.Ticket, "卡密兑换", "卡密兑换"),
    QuickAction(PhosphorIcons.Regular.Coin, "金币提现", "金币提现"),
)

/**
 * 快捷入口：四个圆形图标一行等分，对应参考布局里「已付款 / 待付款 / 待配送 / 退款」那一排。
 *
 * 业务上没有订单状态，这里换成我们自己的四个高频入口；点击只上抛文案，
 * 由 `:app` 的 `navigateByEntryLabel` 落到具体路由（未注册的入口保持无跳转，不造导航）。
 */
@Composable
internal fun UserCenterQuickActions(
    onEntryClick: (String) -> Unit,
    /**
     * 会员体系是否开放。关闭时隐藏"卡密兑换"：它落在会员下单页里，
     * 会员页消失后点进去只会是空页。金币卡密仍可由运营后台手动核销。
     */
    memberEnabled: Boolean = true,
) {
    val actions = QUICK_ACTIONS.filter { memberEnabled || it.entry != ENTRY_CARD }
    Surface(
        shape = AppShape.extraLarge,
        color = starveilExtraColors.surfaceCard,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            actions.forEach { action ->
                QuickActionItem(
                    action = action,
                    onClick = { onEntryClick(action.entry) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun QuickActionItem(action: QuickAction, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(AppShape.medium)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(ACTION_TILE)
                .clip(AppShape.full)
                .background(starveilExtraColors.chipBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = action.label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(IconSize.item),
            )
        }
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelSmall,
            color = starveilExtraColors.textMuted,
            maxLines = 1,
        )
    }
}

/** 圆形图标 44dp：比头像小一档，一行四个在 360dp 屏上不会挤。 */
private val ACTION_TILE = Spacing.xxl + Spacing.md

/** 快捷入口里唯一依赖会员页的项：会员关闭时随会员页一起隐藏。 */
private const val ENTRY_CARD = "卡密兑换"
