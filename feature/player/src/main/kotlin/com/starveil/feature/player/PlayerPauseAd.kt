package com.starveil.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.X
import com.starveil.core.common.ad.AdPlacement
import com.starveil.core.designsystem.component.AD_WIDE_RATIO
import com.starveil.core.designsystem.component.AdCreativeCard
import com.starveil.core.designsystem.component.LocalAdClick
import com.starveil.core.designsystem.component.LocalAdPlacements
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.PlaybackPhase
import kotlinx.coroutines.delay

/**
 * 贴片占画面的大小：宽度取 62%，高度再按 16:9 约束一次。
 *
 * 之前是 86%——横屏全屏下几乎铺满，看不出"这是暂停态覆盖在画面上的一张卡"；
 * 62% 能把视频本体和播控留出来，也更接近主流播放器的暂停广告比例。
 */
private const val INNER_SCALE = 0.62f

/** 倒计时步长：1 秒一跳，与界面上显示的「3s」一致。 */
private const val PAUSE_TICK_MS = 1_000L

/**
 * 暂停贴片层：读广告位、记住关闭状态、按播放状态决定是否出现。
 *
 * 显隐判断收在这一层，[PlayerScreen] 的主函数只多一次调用——那边已经很长了。
 */
@Composable
internal fun PlayerPauseAdLayer(
    state: PlayerUiState,
    onHold: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val creative = LocalAdPlacements.current.pause
    // 关闭只作用于「这一集 + 这一条线路」：换集或换线路算一次新的起播，贴片可以再出现；
    // 旋屏、回前台、进程被回收后重建都还记得关过（rememberSaveable）。
    var dismissed by rememberSaveable(state.episodeIndex, state.sourceIndex) {
        mutableStateOf(false)
    }
    // 每次从播放转为暂停都重新计时：同一集里暂停三次就是三次曝光，不能只让第一次等。
    // 用 remember（不跨重建）而不是 rememberSaveable：旋屏后重新计时比记住旧秒数更合理。
    var remain by remember(state.episodeIndex, state.sourceIndex, state.isPlaying) {
        mutableIntStateOf(creative.pauseSeconds)
    }
    val visible = pauseAdVisible(state, creative, dismissed)
    // 贴片在屏上 = 暂停中被广告占着。这段时间不允许恢复播放，否则双击一下就能跳过倒计时，
    // 后台配的秒数等于没有（用户实测就是这样）。
    SideEffect { onHold(visible) }
    DisposableEffect(Unit) { onDispose { onHold(false) } }
    if (!visible) return
    // 只在贴片真的在屏上时走秒：不可见时上面的 return 已经拦住，不会偷偷耗尽
    LaunchedEffect(remain, creative.pauseSeconds) {
        if (remain <= 0) return@LaunchedEffect
        delay(PAUSE_TICK_MS)
        remain -= 1
    }
    PlayerPauseAd(
        creative = creative,
        remainSeconds = remain,
        onDismiss = {
            dismissed = true
            onHold(false)
        },
        modifier = modifier,
    )
}

/**
 * 暂停贴片是否出现。
 *
 * - [PlayerUiState.phase] 是 [PlaybackPhase.Playing]：地址已解析、播放器手里有流，
 *   也就是"已经起播"；Idle/Parsing/Failed/Blocked 一律不出现，别把转圈或错误页盖上层广告。
 * - `!isPlaying`：暂停态；播放中绝不出现。
 * - `!isBuffering`：还在缓冲是"等流"不是"暂停"，这时出现会盖住加载提示。
 * - `errorMessage == null`：出错时让位给 [PlayerOverlay] 的错误覆盖层。
 * - [AdPlacement.drawable]：没有素材就整层不画，零占位（[AdCreativeCard] 没有空态）。
 *
 * 刻意**不跟随控制层**：暂停态点一下屏幕是"隐藏播控"，不是"关掉广告"，贴片要留在画面上，
 * 否则用户每次点屏幕都会看到广告闪一下又回来。
 */
private fun pauseAdVisible(
    state: PlayerUiState,
    creative: AdPlacement,
    dismissed: Boolean,
): Boolean = creative.drawable && !dismissed && state.errorMessage == null &&
    state.phase is PlaybackPhase.Playing && state.isPauseAdEligible && !state.isPlaying && !state.isBuffering

/**
 * 暂停贴片：16:9 的卡片，占画面的 86% 并居中（不铺满），右上角带倒计时/关闭键。
 *
 * 调用方已按 [pauseAdVisible] 过滤，这里不画空态。
 *
 * 层级：本层排在 [PlayerGestureLayer] 之后、[PlayerOverlay] 与 [PlayerChromeOverlay] 之前，
 * 所以铺满了画面也压不住控制层——底栏的播放键在贴片之上，按下照常继续播。
 *
 * [remainSeconds] > 0 时右上角显示剩余秒数、关闭键暂不可点：这是"别一暂停就关掉"，
 * 到点后自动换成关闭键。取 0 表示后台关掉了这项限制。
 */
@Composable
internal fun PlayerPauseAd(
    creative: AdPlacement,
    remainSeconds: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openLanding = LocalAdClick.current
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        // 贴片是"画面中央的一张卡"，不铺满：留出四周至少 7% 的边距，
        // 用户在暂停态仍能看见画面本体与播控按钮，不会以为进了另一个页面。
        val adWidth = minOf(maxWidth * INNER_SCALE, maxHeight * INNER_SCALE * AD_WIDE_RATIO)
        Box(modifier = Modifier.width(adWidth)) {
            AdCreativeCard(
                creative = creative,
                onClick = { if (creative.clickUrl.isNotBlank()) openLanding(creative.clickUrl) },
                aspectRatio = AD_WIDE_RATIO,
            )
            // 倒计时期间只画秒数，不画关闭键：给一个点不动的叉，比不给更让人恼
            if (remainSeconds > 0) {
                Text(
                    text = "${remainSeconds}s",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Spacing.sm)
                        .clip(AppShape.full)
                        .background(starveilExtraColors.chipBackground)
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                )
            } else {
                ChromeIconButton(
                    icon = PhosphorIcons.Regular.X,
                    description = "关闭广告",
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Spacing.sm),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}
