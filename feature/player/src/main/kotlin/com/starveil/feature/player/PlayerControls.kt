package com.starveil.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ChatCircle
import com.adamglin.phosphoricons.regular.CornersIn
import com.adamglin.phosphoricons.regular.CornersOut
import com.adamglin.phosphoricons.regular.Pause
import com.adamglin.phosphoricons.regular.Play
import com.starveil.core.designsystem.mirrorIconInRtl
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing
import androidx.compose.runtime.State
import com.starveil.core.designsystem.interaction.rememberHaptics
import com.starveil.core.designsystem.interaction.HapticKind

/**
 * 底部控制行高度 44dp：触摸目标与视觉重心兼顾。
 *
 * 暂停贴片读它来算「底栏占位」，关闭键因此不会压到底栏按钮上（见 [PlayerPauseAd]）。
 */
internal val ControlRowHeight: Dp = Spacing.xxl + Spacing.md

/** 倍速胶囊高度 28dp：比按钮矮一档，不跟播放/全屏抢主次。 */
private val SpeedChipHeight: Dp = Spacing.xl + Spacing.xs

/** 底部渐变末端遮罩强度：保证时间与按钮在亮画面上也能读。 */
private const val BOTTOM_SCRIM_ALPHA = 0.92f

/**
 * 底部操作条：播放 / 时间 / 进度 / 倍速 / 弹幕 / 全屏，一条主轴线。
 *
 * 把播放/时间/进度/全屏交给 media3 自带的 PlayerView 控制器，视觉是默认主题
 * （白色实心图标 + 48dp 黑条），和应用的暗色令牌对不上；这里全部自绘，
 * 官方控制器关掉（`useController = false`）。
 *
 * 全屏不再放弹幕输入框：横屏里一行输入框会压掉半部画面，发弹幕走底栏的对话图标 → 底部弹层。
 */
@Composable
internal fun PlayerBottomBar(
    state: PlayerUiState,
    actions: PlayerActions,
    callbacks: BottomBarCallbacks,
    /** 播放位置：只在叶子读，底栏本身不因位置刷新而重组。 */
    position: State<Long>,
    modifier: Modifier = Modifier,
    applyInsets: Boolean = false,
    danmuEnabled: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to starveilExtraColors.scrimStrong.copy(alpha = BOTTOM_SCRIM_ALPHA),
                ),
            )
            // 全屏时系统栏已隐藏，用固定内边距；inset 会让左右留距不对称
            .padding(bottom = if (applyInsets) Spacing.md else Spacing.none)
            .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        ControlRow(
            state = state,
            actions = actions,
            callbacks = callbacks,
            extras = ControlExtras(position = position, danmuEnabled = danmuEnabled),
        )
    }
}

/** 底栏回调：交互（重置自动隐藏）、开底部弹层（选集 / 倍速 / 弹幕）。 */
internal class BottomBarCallbacks(
    val onInteraction: () -> Unit,
    val onOpenSheet: (PlayerSheetKind) -> Unit,
    /** 点倍速胶囊：切下一档，按钮上直接显示当前档位。 */
    val onCycleSpeed: () -> Unit,
)

/**
 * 控制行的两个额外输入：播放位置与弹幕开关。
 *
 * 收成 data class 是为了守住 detekt 的 `LongParameterList`（data class 的构造参数不计入）。
 * 它在 [PlayerBottomBar] 里每次重组都会新建，但只作为参数向下传，不参与记忆：
 * `position` 是同一个 State 实例、`danmuEnabled` 是布尔，按值比较仍相等，
 * 不会把控制行这棵子树额外标脏，所以不必用 remember 包住。
 */
private data class ControlExtras(
    /** 播放位置：只在两个叶子里读，控制行本身不因 2Hz 位置刷新重组。 */
    val position: State<Long>,
    val danmuEnabled: Boolean,
)

/** 播放键 / 时间 / 进度 / 倍速 / 弹幕 / 全屏，从左到右一条主轴线。 */
@Composable
private fun ControlRow(
    state: PlayerUiState,
    actions: PlayerActions,
    callbacks: BottomBarCallbacks,
    extras: ControlExtras,
) {
    val haptics = rememberHaptics()
    Row(
        modifier = Modifier.fillMaxWidth().height(ControlRowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        ChromeIconButton(
            icon = if (state.isPlaying) PhosphorIcons.Regular.Pause else PhosphorIcons.Regular.Play,
            description = if (state.isPlaying) "暂停" else "播放",
            onClick = {
                haptics(HapticKind.Tick)
                callbacks.onInteraction()
                actions.onPlayPause()
            },
            glass = false,
        )
        // 位置只在这两个叶子里读：底栏其余控件不跟着 2Hz 位置刷新重组
        PositionTimeText(position = extras.position, color = MaterialTheme.colorScheme.onBackground)
        PositionProgressBar(
            position = extras.position,
            durationMs = state.durationMs,
            contentColor = MaterialTheme.colorScheme.onBackground,
            onSeekTo = {
                callbacks.onInteraction()
                actions.onSeekTo(it)
            },
            modifier = Modifier.weight(1f),
        )
        TimeText(ms = state.durationMs, color = starveilExtraColors.textMuted)
        if (state.isFullscreen) {
            SpeedChip(speed = state.speed) {
                haptics(HapticKind.Tick)
                callbacks.onInteraction()
                callbacks.onCycleSpeed()
            }
        }
        // 弹幕开关关掉时，发送/设置入口一起收起来：面板改的全是看不见的弹幕，留着只会迷惑
        if (extras.danmuEnabled) {
            ChromeIconButton(
                icon = PhosphorIcons.Regular.ChatCircle,
                mirrorRtl = true,
                description = "弹幕设置",
                onClick = {
                    haptics(HapticKind.Tick)
                    callbacks.onInteraction()
                    callbacks.onOpenSheet(PlayerSheetKind.Danmu)
                },
                glass = false,
            )
        }
        ChromeIconButton(
            icon = if (state.isFullscreen) {
                PhosphorIcons.Regular.CornersIn
            } else {
                PhosphorIcons.Regular.CornersOut
            },
            description = if (state.isFullscreen) "退出全屏" else "全屏",
            onClick = {
                haptics(HapticKind.Tick)
                callbacks.onInteraction()
                actions.onFullscreenToggle()
            },
            glass = false,
        )
    }
}

/** 倍速胶囊：1.0x 时显示"倍速"，其余直接显示档位，省一列宽度。 */
@Composable
private fun SpeedChip(speed: Float, onClick: () -> Unit) {
    // 始终显示当前档位（1.0x 也显示），点一下切下一档：不再弹选择层
    val label = speedLabel(speed)
    Box(
        modifier = Modifier
            .height(SpeedChipHeight)
            .clip(AppShape.full)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = CHROME_GLASS_ALPHA))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
        )
    }
}

@Composable
private fun TimeText(ms: Long, color: Color) {
    Text(text = formatTime(ms), style = MaterialTheme.typography.labelMedium, color = color)
}

/** 当前播放位置：位置流只在这里读，重组范围就是这一行字。 */
@Composable
private fun PositionTimeText(position: State<Long>, color: Color) {
    TimeText(ms = position.value, color = color)
}

/** 进度条：同样把位置读取压在叶子这一层，底栏其余控件不受 2Hz 位置刷新影响。 */
@Composable
private fun PositionProgressBar(
    position: State<Long>,
    durationMs: Long,
    contentColor: Color,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerProgressBar(
        positionMs = position.value,
        durationMs = durationMs,
        contentColor = contentColor,
        onSeekTo = onSeekTo,
        modifier = modifier,
    )
}

internal fun speedLabel(speed: Float): String =
    if (speed % 1f == 0f) "%.1fx".format(speed) else "${speed}x"

internal fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val seconds = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
