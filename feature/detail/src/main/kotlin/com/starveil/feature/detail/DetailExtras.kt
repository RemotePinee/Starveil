package com.starveil.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Lock
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.TrySeeAccess
import com.starveil.core.model.TrySeeGate
import com.starveil.core.model.TrySeeRules

/**
 * 试看提示条（§18.3 的「VIP/试看提示条」）。
 *
 * 判定口径与播放器共用 [TrySeeRules]：免费不显示，需会员/需付费才出现。
 * 详情页不自己请求试看接口（那是起播时 `AccessRepository.checkTrySee` 的活），
 * 所以 [trySee] 为 null 时整条不渲染——不猜"可能要收费"吓用户。
 */
@Composable
internal fun DetailTrySeeBar(trySee: TrySeeGate?) {
    val gate = trySee ?: return
    if (gate.access == TrySeeAccess.FREE) return
    val (title, hint) = trySeeCopy(gate)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.sm)
            .clip(AppShape.small)
            .background(starveilExtraColors.chipBackground)
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(
            imageVector = PhosphorIcons.Regular.Lock,
            contentDescription = null,
            tint = starveilExtraColors.brandGold,
            modifier = Modifier.size(IconSize.md),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = starveilExtraColors.brandGold,
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = starveilExtraColors.textMuted,
            )
        }
    }
}


/** 提示条文案：先给结论，再给"看多久/怎么解锁"。 */
private fun trySeeCopy(gate: TrySeeGate): Pair<String, String> = when (gate.access) {
    TrySeeAccess.NEED_VIP -> "会员专享" to trialHint(gate.trySeeMinutes, "开通会员可看全集")
    TrySeeAccess.NEED_PAY -> "需要付费解锁" to trialHint(gate.trySeeMinutes, "解锁后可看全集")
    TrySeeAccess.FREE -> "" to ""
}

/** 888 是服务端"直接拦截、不给试看时长"的约定值（`TrySeeRules.BLOCK_IMMEDIATELY_MINUTES`）。 */
private fun trialHint(minutes: Int, tail: String): String = when {
    minutes == TrySeeRules.BLOCK_IMMEDIATELY_MINUTES -> "本集不可免费观看，$tail"
    minutes > 0 -> "可试看 $minutes 分钟，$tail"
    else -> tail
}

