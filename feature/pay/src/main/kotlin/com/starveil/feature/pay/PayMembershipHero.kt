package com.starveil.feature.pay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Star
import com.adamglin.phosphoricons.regular.CheckCircle
import com.adamglin.phosphoricons.regular.Star
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing

/**
 * 会员页 Hero：金色标题 + 当前会员状态 + 三条权益，压在一层深色渐变上。
 *
 * 状态三档由 [PayUiState.memberStatusLabel] 给（未登录 / 未开通 / 到期日），本组件不读时间、
 * 不猜默认值；[PayUiState.isMember] 只决定星标用会员金还是三级文字色。
 *
 * 权益文案写死在展示层 [MEMBER_BENEFITS]：后端 `v1.user/detail`
 * 没有权益字段也没有权益接口，在状态里造一个 `benefits` 只会让 UI 以为真有数据源。
 */
@Composable
internal fun PayMembershipHero(state: PayUiState) {
    Surface(
        shape = AppShape.large,
        color = starveilExtraColors.surfaceCard,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(heroBrush())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            HeroHeader(state = state)
            MEMBER_BENEFITS.forEach { benefit ->
                BenefitRow(text = benefit)
            }
        }
    }
}

@Composable
private fun HeroHeader(state: PayUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Column(
            modifier = Modifier.weight(WEIGHT_FULL),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            Text(
                text = HERO_TITLE,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = starveilExtraColors.brandGold,
                maxLines = 1,
            )
            Text(
                text = state.memberStatusLabel,
                style = MaterialTheme.typography.bodySmall,
                color = starveilExtraColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = if (state.isMember) PhosphorIcons.Fill.Star else PhosphorIcons.Regular.Star,
            contentDescription = null,
            tint = if (state.isMember) starveilExtraColors.brandGold else starveilExtraColors.textFaint,
            modifier = Modifier.size(IconSize.md),
        )
    }
}

@Composable
private fun BenefitRow(text: String) {
    Row(
        modifier = Modifier.padding(top = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            imageVector = PhosphorIcons.Regular.CheckCircle,
            contentDescription = null,
            tint = starveilExtraColors.brandGold,
            modifier = Modifier.size(IconSize.sm),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = starveilExtraColors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Hero 底色：顶部一层会员金薄雾，往下压回卡片底色，形成深色渐变。
 *
 * 金色只占顶部约三分之一且透明度不足三成，标签与权益文字仍是浅色，不出现整块土黄。
 */
@Composable
private fun heroBrush(): Brush {
    val wash = starveilExtraColors.brandGold.copy(alpha = GOLD_WASH_ALPHA)
    return Brush.verticalGradient(
        0f to wash,
        GOLD_WASH_STOP to starveilExtraColors.surfaceElevated,
        1f to starveilExtraColors.surfaceCard,
    )
}

private const val HERO_TITLE = "Starveil 会员"

/** Hero 顶部金色薄雾的透明度与止点，以及两端对齐权重（重写骨架时被误删过）。 */
private const val GOLD_WASH_ALPHA = 0.28f
private const val GOLD_WASH_STOP = 0.55f
private const val WEIGHT_FULL = 1f

private val MEMBER_BENEFITS = listOf(
    "会员专区内容免费看",
    "高清画质 · 全集无广告",
    "专属客服 · 新片抢先看",
)
