package com.starveil.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import com.starveil.core.designsystem.mirrorIconInRtl
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.designsystem.token.Spacing
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.FastForward
import com.adamglin.phosphoricons.regular.SpeakerHigh
import com.adamglin.phosphoricons.regular.SunDim

private const val HINT_LINGER_MS = 600L
private const val HINT_SCRIM_ALPHA = 0.72f
private const val DECIDE_PX = 12f

/** 三个手势方向配一枚图标，文案与数值仍由 [GestureSession] 给，行为不变。 */
private enum class GestureMode(val icon: ImageVector) {
    Brightness(PhosphorIcons.Regular.SunDim),
    Volume(PhosphorIcons.Regular.SpeakerHigh),
    Seek(PhosphorIcons.Regular.FastForward),
}

/** 手势提示的内容：图标 + 文案，统一成一条，避免两处状态不同步。 */
private data class HintUi(val icon: ImageVector, val text: String)

/** 手势回调集合，避免 Composable 参数超过 detekt 阈值。 */
internal data class GestureCallbacks(
    val onTap: () -> Unit,
    /** 双击画面：播放/暂停（B 站同款）。 */
    val onDoubleTap: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onInteraction: () -> Unit,
)

/** 手势区：单击切显隐，左半屏上下滑调亮度、右半屏调音量、横滑调进度。 */
@Composable
internal fun PlayerGestureLayer(
    state: PlayerUiState,
    enabled: Boolean,
    callbacks: GestureCallbacks,
    /** 页面持有的亮度/音量读写器：页面销毁时由它还原窗口亮度，见 [PlayerScreen]。 */
    levels: GestureLevels,
    modifier: Modifier = Modifier,
) {
    var hint by remember { mutableStateOf<HintUi?>(null) }
    var hintVisible by remember { mutableStateOf(false) }
    val position = rememberUpdatedState(state.positionMs)
    val duration = rememberUpdatedState(state.durationMs)
    val seek = rememberUpdatedState(callbacks.onSeek)
    val interaction = rememberUpdatedState(callbacks.onInteraction)
    LaunchedEffect(hint, hintVisible) {
        if (hintVisible) {
            delay(HINT_LINGER_MS)
            hintVisible = false
        }
    }
    val doubleTap = rememberUpdatedState(callbacks.onDoubleTap)
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                // 不用 detectTapGestures：带 onDoubleTap 时它会等双击超时（约 300ms）才回调
                // onTap，表现就是"点一下，控制层慢半拍才出来"。这里抬手立刻切显隐，
                // 再在双击超时窗口内看有没有第二下，有就当双击处理。
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                    up.consume()
                    callbacks.onTap()
                    val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                        awaitFirstDown(requireUnconsumed = false)
                    } ?: return@awaitEachGesture
                    second.consume()
                    waitForUpOrCancellation()
                    doubleTap.value()
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val session = GestureSession()
                detectDragGestures(
                    onDragStart = { offset ->
                        val frame = GestureFrame(size.width, size.height, levels)
                        session.begin(offset.x, position.value, duration.value, frame)
                        interaction.value()
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        val frame = GestureFrame(size.width, size.height, levels)
                        session.update(amount.x, amount.y, frame)?.let {
                            hint = it
                            hintVisible = true
                        }
                    },
                    onDragEnd = { if (session.mode == GestureMode.Seek) seek.value(session.seekTo) },
                )
            },
    ) {
        AnimatedVisibility(
            visible = hintVisible,
            enter = fadeIn(Motion.short),
            exit = fadeOut(Motion.short),
            modifier = Modifier.align(Alignment.Center),
        ) {
            hint?.let { GestureHint(it) }
        }
    }
}

/** 居中胶囊：深色遮罩 + 图标 + 数值，淡入淡出，不打断画面。 */
@Composable
private fun GestureHint(hint: HintUi, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(AppShape.full)
            .background(starveilExtraColors.scrimStrong.copy(alpha = HINT_SCRIM_ALPHA))
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    ) {
        Icon(
            imageVector = hint.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(IconSize.sm).mirrorIconInRtl(),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = hint.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** 一帧的尺寸与系统控制器；把 5 个参数压到 3 个。 */
private data class GestureFrame(val width: Int, val height: Int, val levels: GestureLevels)

/** 拖动过程的状态机：先按首次位移判方向，再按方向换算数值并落到系统。 */
private class GestureSession {

    var mode: GestureMode? = null
        private set
    var seekTo: Long = 0L
        private set

    private var left = true
    private var totalX = 0f
    private var totalY = 0f
    private var startBrightness = DEFAULT_BRIGHTNESS
    private var startVolume = 0f
    private var seekFrom = 0L
    private var durationMs = 0L

    fun begin(x: Float, position: Long, duration: Long, frame: GestureFrame) {
        val width = frame.width
        val levels = frame.levels
        left = x < width / 2f
        totalX = 0f
        totalY = 0f
        mode = null
        seekFrom = position
        seekTo = position
        startBrightness = levels.brightness
        startVolume = levels.volume
        durationMs = duration
    }

    fun update(dx: Float, dy: Float, frame: GestureFrame): HintUi? {
        val width = frame.width
        val height = frame.height
        val levels = frame.levels
        totalX += dx
        totalY += dy
        if (mode == null) mode = decideMode()
        return when (mode) {
            GestureMode.Brightness -> level(height, startBrightness, "亮度", levels::setBrightness)
            GestureMode.Volume -> level(height, startVolume, "音量", levels::setVolume)
            GestureMode.Seek -> seek(width)
            null -> null
        }
    }

    private fun decideMode(): GestureMode? {
        if (abs(totalX) < DECIDE_PX && abs(totalY) < DECIDE_PX) return null
        return when {
            abs(totalX) > abs(totalY) -> GestureMode.Seek
            left -> GestureMode.Brightness
            else -> GestureMode.Volume
        }
    }

    private fun level(height: Int, start: Float, label: String, apply: (Float) -> Unit): HintUi? {
        val value = (start - totalY / height.coerceAtLeast(1)).coerceIn(0f, 1f)
        apply(value)
        val mode = mode ?: return null
        return HintUi(mode.icon, "$label ${(value * 100).toInt()}%")
    }

    private fun seek(width: Int): HintUi {
        val span = maxOf(durationMs, 1L)
        seekTo = (seekFrom + totalX / width.coerceAtLeast(1) * span).toLong().coerceIn(0L, span)
        return HintUi(GestureMode.Seek.icon, "进度 ${formatTime(seekTo)} / ${formatTime(durationMs)}")
    }
}
