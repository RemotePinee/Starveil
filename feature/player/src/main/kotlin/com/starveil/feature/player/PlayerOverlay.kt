package com.starveil.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.PlaybackPhase
import com.starveil.core.model.TrySeeCopy

/** 解析阶段：还没拿到播放地址，文案是"正在接入线路"。 */
private const val MSG_LOADING = "正在接入播放线路…"

/**
 * 已起播但没帧可画：这是缓冲，不是解析。
 *
 * 之前两种状态共用 MSG_LOADING，用户看到的是"一直在接入播放线路"，分不清是站点解析慢
 * 还是自己的网慢——排查和体感都差。
 */
private const val MSG_BUFFERING = "缓冲中…"

private const val MSG_PARSE_FAILED = "线路解析失败，请重试"

private const val MSG_RETRY = "重试"

/** 缓冲卡死时的提示：不报错、不重开线路，只给用户一个"重试"的出口。 */
private const val MSG_STALLED = "线路没有响应"

private const val STALLED_SCRIM_ALPHA = 0.72f

/** 主按钮最小宽度：短文案也能撑成一块完整的 CTA。 */
private val MinActionWidth: Dp = Spacing.xxl * 4

/**
 * 三态覆盖层：解析中 / 全部线路失败 / 试看拦截。播放中不覆盖，避免挡住画面。
 *
 * 试看拦截的判定与文案来自 TrySeeRules 与 PlayerStateMapper，这里只负责显示。
 */
@Composable
internal fun PlayerOverlay(
    state: PlayerUiState,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
    unlock: UnlockOptions = UnlockOptions(),
    onWatchAd: () -> Unit = {},
) {
    when (state.phase) {
        is PlaybackPhase.Parsing, PlaybackPhase.Idle -> PlaybackVeil(label = MSG_LOADING, modifier = modifier)
        is PlaybackPhase.Failed -> FailedOverlay(state.errorMessage.orEmpty(), actions.onRetry, modifier)
        is PlaybackPhase.Blocked -> BlockedOverlay(
            ui = BlockedUi(
                notice = state.trySeeNotice.orEmpty(),
                unlock = unlock,
                onUpgrade = actions.onUpgrade,
                onBuy = actions.onBuy,
                onWatchAd = onWatchAd,
            ),
            modifier = modifier,
        )

        is PlaybackPhase.Playing -> when {
            state.errorMessage != null -> FailedOverlay(state.errorMessage.orEmpty(), actions.onRetry, modifier)
            state.isBuffering -> PlaybackVeil(label = MSG_BUFFERING, modifier = modifier)
            else -> Unit
        }
    }
}

@Composable
private fun OverlayScaffold(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = Spacing.xl),
            content = content,
        )
    }
}
/**
 * 卡死兜底：缓冲既没时长也没进度，说明这条线路没在出流。
 *
 * 与其让用户对着转圈等到放弃，不如给一个明确的重试入口（重试会清空失败集合重开）。
 */
@Composable
internal fun StalledHint(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(AppShape.medium)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = STALLED_SCRIM_ALPHA))
            .padding(horizontal = Spacing.xl, vertical = Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = MSG_STALLED,
            style = MaterialTheme.typography.bodyMedium,
            color = starveilExtraColors.textMuted,
        )
        Spacer(Modifier.height(Spacing.sm))
        TextButton(onClick = onRetry) { Text(text = MSG_RETRY) }
    }
}

@Composable
private fun FailedOverlay(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    OverlayScaffold(modifier) {
        Text(
            text = message.ifBlank { MSG_PARSE_FAILED },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.lg))
        Button(onClick = onRetry, shape = AppShape.small, modifier = Modifier.widthIn(min = MinActionWidth)) {
            Text(MSG_RETRY)
        }
    }
}

/** 试看拦截的参数包，避免 Composable 超过 5 个参数。 */
private data class BlockedUi(
    val notice: String,
    val unlock: UnlockOptions,
    val onUpgrade: () -> Unit,
    val onBuy: () -> Unit,
    val onWatchAd: () -> Unit,
)

/**
 * 试看拦截：标题 + 说明 + 出口按钮。
 *
 * 三个出口**并排成一行**、等分宽度、药丸形，顺序固定：会员、积分、广告。
 * 会员体系关闭时服务端 `can_vip=0`，这里不会渲染会员按钮；
 * 广告未接入时 `can_ad=0` 且网关 Noop，同样不渲染。
 */
@Composable
private fun BlockedOverlay(ui: BlockedUi, modifier: Modifier = Modifier) {
    OverlayScaffold(modifier) {
        Text(
            text = TrySeeCopy.TITLE,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = noticeOf(ui),
            style = MaterialTheme.typography.bodyMedium,
            color = starveilExtraColors.textMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (ui.unlock.canUpgrade) {
                UnlockButton(label = ACTION_UPGRADE, onClick = ui.onUpgrade, modifier = Modifier.weight(1f))
            }
            if (ui.unlock.canRedeemPoints) {
                UnlockButton(
                    label = redeemLabel(ui.unlock.pointsPrice),
                    onClick = ui.onBuy,
                    modifier = Modifier.weight(1f),
                )
            }
            if (ui.unlock.canWatchAd) {
                UnlockButton(label = ACTION_WATCH_AD, onClick = ui.onWatchAd, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * 药丸解锁按钮：所有出口同排等分宽度，文案强制单行。
 *
 * 三个出口并排时每个只剩三分之一宽度，字号收到 labelSmall 才有机会放下"199 积分兑换本集"；
 * 实在放不下就省略号截断，不折行——折行会让三个按钮高度不齐，整排看着歪。
 */
@Composable
private fun UnlockButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        shape = AppShape.full,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = Spacing.xs, vertical = Spacing.sm),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 说明文案：单一开关后会员 / 积分 / 广告共用一套，不再按"能走哪些出口"拼。
 * `ui.notice` 优先（状态层已经算好），空串时回落到统一文案。
 */
private fun noticeOf(ui: BlockedUi): String = ui.notice.ifBlank { TrySeeCopy.HINT }

/** 有价显示数字，没配价退回通用文案。 */
private fun redeemLabel(points: Int): String =
    if (points > 0) "$points 积分兑换本集" else ACTION_REDEEM

/** 买断本集在客户端统一叫「积分兑换」：服务端 `Payment::buyEpisode` 扣的就是积分。 */
private const val ACTION_REDEEM = "积分兑换"

private const val ACTION_UPGRADE = "开通会员"

private const val ACTION_WATCH_AD = "看广告解锁本集"
