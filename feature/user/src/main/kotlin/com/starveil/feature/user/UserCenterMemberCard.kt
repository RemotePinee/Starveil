package com.starveil.feature.user

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
    /** 会员卡渐变左端（偏亮墨绿）。 */
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Brush
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing

/**
 * 会员卡：一张"压在底图上的深色卡片"，左侧图标 + 三行文字，右侧圆形开通按钮。
 *
 * 底图 [R.drawable.feature_member_card] 自带深棕金渐变与右侧暗块，所以这张卡**不跟主题**：
 * 深浅模式下都是同一张图、同一套金色文字。浅色主题的 surface 系文字是深色，压在这张图上
 * 会糊掉，因此三行文字统一取 [starveilExtraColors.brandGold]，与左侧图标同色。
 *
 * 会员体系关闭（`member_enabled = 0`）时：**卡片照常显示、底图照常、文字照常**，
 * 只是右侧那个开通按钮不渲染——历史会员要能看到"权益保留至到期"，非会员要看到
 * "暂未开放"，两种信息都在副标题里由 [memberStatusText] 给出。
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
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppShape.extraLarge),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        0f to starveilExtraColors.memberMossStart,
                        1f to starveilExtraColors.memberMossEnd,
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_member_unlock),
                contentDescription = null,
                modifier = Modifier.size(Spacing.xxl),
            )
            MemberCardTexts(
                state = state,
                modifier = Modifier.weight(WEIGHT_FULL),
            )
            // 会员关闭时不渲染按钮：卡片与底图保持原样，只是没有入口可点
            if (enabled) {
                MemberEntryButton(
                    isMember = state.isMember,
                    onEntryClick = onEntryClick,
                )
            }
        }
    }
}

/** 标题 / 会员状态 / 权益摘要三段文字，统一用金色（与左侧图标同色）。 */
@Composable
private fun MemberCardTexts(
    state: UserCenterUiState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = MEMBER_TITLE,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = starveilExtraColors.memberText,
            maxLines = 1,
        )
        Text(
            text = memberStatusText(state = state),
            style = MaterialTheme.typography.bodySmall,
            color = starveilExtraColors.memberText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 权益文案并进左列：原来它单独占卡片底部一行，
        // 右上的按钮只对前两行居中，相对整张卡就偏上了。
        // 会员关闭时也照常显示权益列表：已开通的人这些权益还在，没开通的人
        // 需要知道买的是什么。「开通已关闭」那句已经由副标题说了，这里再说一遍就是重复。
        Text(
            text = BENEFIT_SUMMARY,
            style = MaterialTheme.typography.labelSmall,
            color = starveilExtraColors.memberText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 圆形按钮：金底 + 深色箭头图标。
 *
 * 文案「开通 / 续费」不再显示——参考设计里这里是纯图标，文案由左侧标题承担。
 * `contentDescription` 仍然给出，读屏用户才知道这里能点。
 */
@Composable
private fun MemberEntryButton(
    isMember: Boolean,
    onEntryClick: (String) -> Unit,
) {
    val label = if (isMember) ACTION_RENEW else ACTION_SUBSCRIBE
    // 图标本身自带金底圆环：这里不再套 background(brandGold) 的圆形容器，
    // 否则会在图标外面多出一圈纯金色边，看起来像双层。
    Image(
        painter = painterResource(R.drawable.ic_member_arrow),
        contentDescription = label,
        modifier = Modifier
            // 比卡片左侧图标再大一号：它是这张卡唯一的可点目标，小了不好点
            .size(Spacing.xxl + Spacing.md)
            .clickable { onEntryClick(MEMBER_ENTRY_LABEL) },
    )
}

private const val MEMBER_TITLE = "Starveil 会员"
private const val BENEFIT_SUMMARY = "会员专区免费看 · 高清无广告 · 新片抢先看"

/** 会员卡按钮上抛的入口文案；`:app` 用它路由到会员页，会员关闭时不会再触发。 */
const val MEMBER_ENTRY_LABEL = "开通会员"
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

private const val WEIGHT_FULL = 1f
