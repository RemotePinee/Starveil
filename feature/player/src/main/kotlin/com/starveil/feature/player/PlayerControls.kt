package com.starveil.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.bold.ChatCircle
import com.adamglin.phosphoricons.bold.CornersIn
import com.adamglin.phosphoricons.bold.CornersOut
import com.adamglin.phosphoricons.bold.Pause
import com.adamglin.phosphoricons.bold.Play
import com.adamglin.phosphoricons.bold.SkipBack
import com.adamglin.phosphoricons.bold.SkipForward
import com.starveil.core.designsystem.interaction.HapticKind
import com.starveil.core.designsystem.interaction.rememberHaptics
import com.starveil.core.designsystem.mirrorIconInRtl
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.designsystem.token.Spacing

/**
 * 底部控制行高度 48dp：图标放大到 24dp 后 44dp 会顶到上下边缘，加一档留出呼吸。
 *
 * 暂停贴片读它来算「底栏占位」，关闭键因此不会压到底栏按钮上（见 [PlayerPauseAd]）。
 */
internal val ControlRowHeight: Dp = Spacing.xxl + Spacing.lg

/** 倍速胶囊高度 32dp：比按钮矮一档，不跟播放/全屏抢主次。 */
private val SpeedChipHeight: Dp = Spacing.xxl

/** 底部渐变末端遮罩强度：保证时间与按钮在亮画面上也能读。 */
private const val BOTTOM_SCRIM_ALPHA = 0.92f

/**
 * 底部操作条：播放 / 时间 / 进度 / 上下集 / 倍速 / 弹幕 / 全屏，一条主轴线。
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
    /**
     * 已生效的全屏态（见 [rememberSettledFullscreen]），不是 `state.isFullscreen`。
     *
     * state 在点下全屏那一帧就翻，画面却要等系统转屏；图标和倍速胶囊若跟 state 走，
     * 就会"先变图标、再铺满"，中途插进来的胶囊还会把进度条挤窄一截。
     */
    fullscreen: Boolean = false,
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
            extras = ControlExtras(
                position = position,
                danmuEnabled = danmuEnabled,
                fullscreen = fullscreen,
            ),
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
 * 控制行的额外输入：播放位置、弹幕开关与已生效的全屏态。
 *
 * 收成 data class 是为了守住 detekt 的 `LongParameterList`（data class 的构造参数不计入）。
 * 它在 [PlayerBottomBar] 里每次重组都会新建，但只作为参数向下传，不参与记忆：
 * `position` 是同一个 State 实例、其余是布尔，按值比较仍相等，
 * 不会把控制行这棵子树额外标脏，所以不必用 remember 包住。
 */
private data class ControlExtras(
    /** 播放位置：只在两个叶子里读，控制行本身不因 2Hz 位置刷新重组。 */
    val position: State<Long>,
    val danmuEnabled: Boolean,
    /** 全屏图标与倍速胶囊的开关：等画面真的铺满再翻，点下去那一刻不动布局。 */
    val fullscreen: Boolean,
)

/** 上一集 / 播放 / 下一集 · 时间 / 进度 / 时长 / 倍速 / 弹幕 / 全屏，一条主轴线。 */
@Composable
private fun ControlRow(
    state: PlayerUiState,
    actions: PlayerActions,
    callbacks: BottomBarCallbacks,
    extras: ControlExtras,
) {
    val haptics = rememberHaptics()
    val timeSlot = rememberTimeSlotWidth(state.durationMs)
    Row(
        modifier = Modifier.fillMaxWidth().height(ControlRowHeight),
        verticalAlignment = Alignment.CenterVertically,
        // 夹得紧：时间贴着进度条，省下的宽度全留给进度条
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        // 上一集在播放键左边、下一集在右边：中间那颗是"此刻在播"的锚点，
        // 换集的两个动作分列两侧，符合播放器惯例（第 1 集没有上一集，最后一集没有下一集）
        EpisodeStep(
            visible = state.episodeIndex > 0,
            icon = PhosphorIcons.Bold.SkipBack,
            description = "上一集",
            onClick = {
                haptics(HapticKind.Tick)
                callbacks.onInteraction()
                actions.onEpisodeSelected(state.episodeIndex - 1)
            },
        )
        ChromeIconButton(
            icon = if (state.isPlaying) PhosphorIcons.Bold.Pause else PhosphorIcons.Bold.Play,
            description = if (state.isPlaying) "暂停" else "播放",
            onClick = {
                haptics(HapticKind.Tick)
                callbacks.onInteraction()
                actions.onPlayPause()
            },
            glass = false,
        )
        EpisodeStep(
            visible = state.episodes.isNotEmpty() && state.episodeIndex < state.episodes.lastIndex,
            icon = PhosphorIcons.Bold.SkipForward,
            description = "下一集",
            onClick = {
                haptics(HapticKind.Tick)
                callbacks.onInteraction()
                actions.onEpisodeSelected(state.episodeIndex + 1)
            },
        )
        // 位置只在这两个叶子里读：底栏其余控件不跟着 2Hz 位置刷新重组
        PositionTimeText(
            position = extras.position,
            color = MaterialTheme.colorScheme.onBackground,
            slot = timeSlot,
        )
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
        TimeText(
            ms = state.durationMs,
            color = starveilExtraColors.textMuted,
            slot = timeSlot,
            align = TextAlign.Start,
        )
        if (extras.fullscreen) {
            SpeedChip(speed = state.speed) {
                haptics(HapticKind.Tick)
                callbacks.onInteraction()
                callbacks.onCycleSpeed()
            }
        }
        // 弹幕开关关掉时，发送/设置入口一起收起来：面板改的全是看不见的弹幕，留着只会迷惑
        if (extras.danmuEnabled) {
            ChromeIconButton(
                icon = PhosphorIcons.Bold.ChatCircle,
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
            icon = if (extras.fullscreen) {
                PhosphorIcons.Bold.CornersIn
            } else {
                PhosphorIcons.Bold.CornersOut
            },
            description = if (extras.fullscreen) "退出全屏" else "全屏",
            onClick = {
                haptics(HapticKind.Tick)
                callbacks.onInteraction()
                actions.onFullscreenToggle()
            },
            glass = false,
        )
    }
}

/**
 * 上一集 / 下一集：出现和消失都走同一段横向展开 + 淡入。
 *
 * 不用 if 直接塞进去：那样按钮会在一帧里凭空出现，左边整条进度条跟着跳一下。
 * 展开动画期间进度条被逐渐挤窄，看起来是"让位"而不是"被打断"。
 */
@Composable
private fun EpisodeStep(
    visible: Boolean,
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.short) + expandHorizontally(tween(Motion.ShortMs)),
        exit = fadeOut(Motion.short) + shrinkHorizontally(tween(Motion.ShortMs)),
    ) {
        ChromeIconButton(
            icon = icon,
            // 两个都是"跳到前一集/后一集"的方向图标，RTL 下要跟着翻
            mirrorRtl = true,
            description = description,
            onClick = onClick,
            glass = false,
        )
    }
}

/** 倍速胶囊：显示当前档位，点一下切下一档。 */
@Composable
private fun SpeedChip(speed: Float, onClick: () -> Unit) {
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
            style = playerChromeLabelStyle(),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
        )
    }
}

/** 时间文字最宽的一档：一位数小时 + 两位分秒（`1:36:30`）。 */
private const val WIDEST_TIME = "8:88:88"

/** 不到一小时的片长：两位数分秒，5 个字宽。 */
private const val SHORT_TIME = "88:88"

/** 一小时以上的门槛：到这一档，时间槽才按 `1:36:30` 的宽度留。 */
private const val HOUR_MS = 3_600_000L

/**
 * 时间文字的槽宽：按**当前片长**量一档，不到一小时只要 5 位。
 *
 * 槽宽是圆点位置的乘数（见 PlayerProgressBar 的 drawProgress），所以这里既不能按最宽的
 * 一档死留（5 位时长会白占两字宽，进度条被压短），也不能每秒跟着文字走（0 比 1 宽，
 * 进度条会抖）。按片长分两档量一次，切换剧集时用动画过渡，布局不硬跳。
 */
@Composable
private fun rememberTimeSlotWidth(durationMs: Long): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // 和 TimeText 用同一个 style：字重字号一改，槽宽和字宽必须一起改
    val style = playerChromeLabelStyle()
    val sample = if (durationMs >= HOUR_MS) WIDEST_TIME else SHORT_TIME
    val target = remember(measurer, density, style, sample) {
        // 再留一根 hairline：量到的宽度是 px 取整值，回算 dp 时不吃亚像素舍入
        with(density) { measurer.measure(text = sample, style = style).size.width.toDp() } +
            Spacing.hairline
    }
    return animateDpAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = Motion.MediumMs, easing = Motion.standard),
        label = "player-time-slot",
    ).value
}

/** 时间文字：宽度锁在槽宽上，内容贴向进度条那一侧（[align]），内容变化不再改行宽。 */
@Composable
private fun TimeText(ms: Long, color: Color, slot: Dp, align: TextAlign) {
    Text(
        text = formatTime(ms),
        style = playerChromeLabelStyle(),
        color = color,
        maxLines = 1,
        textAlign = align,
        modifier = Modifier.width(slot),
    )
}

/** 当前播放位置：位置流只在这里读，重组范围就是这一行字。 */
@Composable
private fun PositionTimeText(position: State<Long>, color: Color, slot: Dp) {
    TimeText(ms = position.value, color = color, slot = slot, align = TextAlign.End)
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
