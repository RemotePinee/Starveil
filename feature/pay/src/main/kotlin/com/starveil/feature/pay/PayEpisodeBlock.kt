package com.starveil.feature.pay

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.starveil.core.designsystem.starveilExtraColors

/**
 * 单集模式的信息块：只回显要买的影片与集数。
 *
 * 旧播放页点"购买本集"直接调接口，没有二次确认页；
 * 保留同一口径：不展示套餐、不展示卡密，只把 [PayArgs] 里的影片/集数摆清楚。
 * 买断扣的是积分，所以没有支付方式可选，底部按钮由外层给。
 */
@Composable
internal fun PayEpisodeBlock(state: PayUiState) {
    PaySectionCard(title = SECTION_TITLE) {
        Text(
            text = "影片 #${state.args.vodId} · 第 ${state.args.episodeNumber} 集",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = CAPTION,
            style = MaterialTheme.typography.bodySmall,
            color = starveilExtraColors.textMuted,
        )
    }
}

private const val SECTION_TITLE = "积分兑换本集"
private const val CAPTION = "按后台设置的单集积分兑换，成功后即可继续观看；积分不足会提示"

