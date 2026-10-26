package com.starveil.feature.user

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.Star
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * 会员卡：左侧标题 + 状态 + 权益摘要，右侧"开通 / 续费"。
 *
 * 底色仍是 [starveilExtraColors.surfaceCard]，金色只作点缀：一层从左向右渐隐的金雾、
 * 金色标题与金色胶囊按钮。历史版本把整张卡刷成土黄渐变 + 三条权益 + 分隔线，既高饱和又把
 * 一屏高度的三分之一让给了一句广告；这里压成单行卡片，完整权益放到支付页 Hero。
 *
 * 权益摘要写死在展示层 [BENEFIT_SUMMARY]：后端 `v1.user/detail`
 * 没有权益字段，也没有权益接口，在状态里造一个 `benefits` 只会让 UI 以为真有数据源。
 *
 * 按钮只上抛 [MEMBER_ENTRY_LABEL]：`:app` 的入口映射把它接到 `feature:pay` 的会员下单页，
 * 本模块不直接引用支付 feature（AGENTS.md 第 1 节）。
 */
@Composable
internal fun MemberCard(
    state: UserCenterUiState,
    onEntryClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = state.memberEnabled
    Surface(
        shape = AppShape.extraLarge,
        color = if (enabled) starveilExtraColors.surfaceCard else starveilExtraColors.chipBackground,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (enabled) Modifier.background(goldWash()) else Modifier)
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(
                imageVector = PhosphorIcons.Fill.Star,
                contentDescription = null,
                tint = if (enabled) starveilExtraColors.brandGold else starveilExtraColors.textFaint,
                modifier = Modifier.size(IconSize.item),
            )
            MemberCardTexts(
                state = state,
                enabled = enabled,
                modifier = Modifier.weight(WEIGHT_FULL),
            )
            // 会员关闭时不渲染按钮：卡片保留（历史会员要看到到期时间），但不能点开通
            if (enabled) {
                MemberEntryButton(
                    isMember = state.isMember,
                    onEntryClick = onEntryClick,
                )
            }
        }
    }
}

/** 标题 / 会员状态 / 权益摘要三段文字。 */
@Composable
private fun MemberCardTexts(
    state: UserCenterUiState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = MEMBER_TITLE,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) starveilExtraColors.brandGold else starveilExtraColors.textMuted,
            maxLines = 1,
        )
        Text(
            text = memberStatusText(state = state),
            style = MaterialTheme.typography.bodySmall,
            color = starveilExtraColors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 权益文案并进左列：原来它单独占卡片底部一行，
        // 右上的按钮只对前两行居中，相对整张卡就偏上了
        Text(
            text = if (enabled) BENEFIT_SUMMARY else CLOSED_BENEFIT,
            style = MaterialTheme.typography.labelSmall,
            color = starveilExtraColors.textFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 金色胶囊按钮：文案随会员状态在「开通 / 续费」间切换。 */
@Composable
private fun MemberEntryButton(
    isMember: Boolean,
    onEntryClick: (String) -> Unit,
) {
    Button(
        onClick = { onEntryClick(MEMBER_ENTRY_LABEL) },
        shape = AppShape.full,
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
        colors = ButtonDefaults.buttonColors(
            containerColor = starveilExtraColors.brandGold,
            contentColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Text(
            text = if (isMember) ACTION_RENEW else ACTION_SUBSCRIBE,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun goldWash(): Brush {
    val wash = starveilExtraColors.brandGold.copy(alpha = GOLD_WASH_ALPHA)
    return Brush.horizontalGradient(
        0f to wash,
        GOLD_WASH_STOP to starveilExtraColors.surfaceCard,
    )
}

private const val MEMBER_TITLE = "Starveil 会员"
private const val BENEFIT_SUMMARY = "会员专区免费看 · 高清无广告 · 新片抢先看"
/** 会员卡按钮上抛的入口文案；`:app` 用它路由到会员页，会员关闭时不会再触发。 */
const val MEMBER_ENTRY_LABEL = "开通会员"
private const val CLOSED_BENEFIT = "会员开通已关闭，已开通的权益保留至到期"
private const val CLOSED_HINT = "会员开通暂未开放，已开通的权益保留至到期"
private const val ACTION_SUBSCRIBE = "开通"
private const val ACTION_RENEW = "续费"
/**
 * 会员卡副标题。
 *
 * 会员状态文案本身由共享的 `MembershipStatus.label`（`core/model/MembershipStatus.kt`）给，
 * 与会员页 Hero 是同一份：未登录 / 未开通 / 到期日 / 占位都在那里，这里只补"开通已关闭"的收尾。
 */
private fun memberStatusText(state: UserCenterUiState): String = when {
    // 会员关闭时卡片保留：历史会员先看到"权益保留至到期"，非会员看到"暂未开放"
    !state.memberEnabled && state.isMember -> "${state.memberExpiryLabel}，开通已关闭"
    !state.memberEnabled -> CLOSED_HINT
    else -> state.memberExpiryLabel
}
private const val GOLD_WASH_ALPHA = 0.22f
private const val GOLD_WASH_STOP = 0.55f
private const val WEIGHT_FULL = 1f
