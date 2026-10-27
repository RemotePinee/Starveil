package com.starveil.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.Spacing

/** 轨道粗细 3dp：比 4dp 栅格细一档，细轨浮在画面上更像现代播放器。 */
private val TrackHeight: Dp = Spacing.xs - Spacing.xxs / 2

/** 圆点直径 12dp：比轨道大出三圈，手指能看清当前进度。 */
private val ThumbSize: Dp = Spacing.md

/** 触摸区高度：24dp，比轨道大，横滑时不用瞄准。 */
private val TouchHeight: Dp = Spacing.xl

private const val TRACK_ALPHA = 0.24f

/** 一条进度条的全部绘制参数：绘制时不再判断 compact，分叉只发生在组装这里。 */
private data class ProgressStyle(
    val compact: Boolean,
    val trackHeight: Dp,
    val trackColor: Color,
    val progressColor: Color,
    val thumbColor: Color,
)


/**
 * 横滑时的预览位置。
 *
 * 手指在画面上左右滑时，进度条要跟着走；只在 `PlayerScreen` 提供，其它地方读到的
 * 是 null，行为和以前一致。用 CompositionLocal 而不是层层传参：进度条在
 * `ControlRow`（私有）里，中间隔着三层。
 */
internal val LocalSeekPreview = compositionLocalOf<Long?> { null }
/**
 * 进度条：已播段主色、未播段是内容色降透明；原生播放器两端各让出一个圆点半径。
 *
 * Material 的 Slider 自带 48dp 高度与固定 thumb，压不进播放器底栏；
 * 自己画，拖动时只动本地预览值，抬手才真正 seek。
 * 原生播放器是 3dp 轨道 + 12dp 圆点；短剧竖屏 [compact] 走 4dp 轨道 / 12dp 圆点，
 * 轨道铺满整条 viewport 且底边压在触摸区下沿（下方入口条顶边），未播段取视频上的次级色，
 * 圆点与轨道同心，只在横向端点内收；同心的下半会探进下方入口条，由调用方抬高层级绘制。
 */
@Composable
internal fun PlayerProgressBar(
    positionMs: Long,
    durationMs: Long,
    contentColor: Color,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val enabled = durationMs > 0L
    var dragging by remember { mutableStateOf<Long?>(null) }
    // 手指在画面上横滑时，进度条要跟着预演位置走；拖动进度条本体时以 dragging 优先。
    // 两者都为 null 才回落到真实播放位置。
    val shown = dragging ?: LocalSeekPreview.current ?: positionMs
    val seek = rememberUpdatedState(onSeekTo)
    val span = rememberUpdatedState(if (enabled) durationMs else 1L)
    // compact 只换轨道粗细、未播段颜色与端点口径：轨道提到 4dp（Spacing.xs），
    // 未播段取视频上的次级色令牌，不在业务里现调 alpha；圆点与 24dp 触摸区不变。
    // 原生非 compact 仍是 3dp 轨道 + 内容色 24% 底轨。
    val style = ProgressStyle(
        compact = compact,
        trackHeight = if (compact) Spacing.xs else TrackHeight,
        trackColor = if (compact) starveilExtraColors.textMuted else contentColor.copy(alpha = TRACK_ALPHA),
        progressColor = MaterialTheme.colorScheme.primary,
        thumbColor = contentColor,
    )
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(TouchHeight)
            .pointerInput(enabled, compact) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset -> seek.value(seekMsAt(offset.x, span.value, compact)) }
            }
            .pointerInput(enabled, compact) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset -> dragging = seekMsAt(offset.x, span.value, compact) },
                    onDragEnd = {
                        dragging?.let { seek.value(it) }
                        dragging = null
                    },
                    onDragCancel = { dragging = null },
                ) { change, _ ->
                    change.consume()
                    dragging = seekMsAt(change.position.x, span.value, compact)
                }
            },
    ) {
        drawProgress(fraction = progressFraction(shown, span.value, enabled), style = style)
    }
}

/**
 * 已播比例。时长还没回来时（prepare 后、duration 回调前）span 兜底是 1ms，
 * 断点位置会让 fraction 直接顶到 1：进度条先闪一下"已播到底"再回落到断点。
 * 这种情况一条已播放的轨道都不该画，跟竖屏播放器同一口径。
 */
private fun progressFraction(positionMs: Long, durationMs: Long, enabled: Boolean): Float =
    if (enabled) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

/** 画底轨、已播段与圆点。端点与 [seekMsAt] 同口径，触点与画面才对得上。 */
private fun DrawScope.drawProgress(fraction: Float, style: ProgressStyle) {
    val trackPx = style.trackHeight.toPx()
    val thumbRadius = ThumbSize.toPx() / 2f
    val trackHalf = trackPx / 2f
    val centerY = progressCenterLineY(style.compact, size.height, trackPx)
    // compact 的轨道铺满 viewport 两边：进度与触点都按 0..width 映射，
    // 圆点只在绘制时夹进两端半径内，端点既不裁切也不给轨道留可见边距。
    val edgeInset = if (style.compact) 0f else thumbRadius
    val trackLeft = edgeInset
    val trackRight = size.width - edgeInset
    val radius = CornerRadius(trackHalf, trackHalf)
    drawRoundRect(
        color = style.trackColor,
        topLeft = Offset(trackLeft, centerY - trackHalf),
        size = Size(trackRight - trackLeft, trackPx),
        cornerRadius = radius,
    )
    val played = trackLeft + (trackRight - trackLeft) * fraction
    drawRoundRect(
        color = style.progressColor,
        topLeft = Offset(trackLeft, centerY - trackHalf),
        size = Size((played - trackLeft).coerceAtLeast(trackPx), trackPx),
        cornerRadius = radius,
    )
    val thumbX = if (style.compact) played.coerceIn(thumbRadius, size.width - thumbRadius) else played
    drawCircle(color = style.thumbColor, radius = thumbRadius, center = Offset(thumbX, centerY))
}

/**
 * 轨道与圆点共用的纵向中线（px）。
 *
 * 只有这一条中心线：圆点必须和轨道同心，不再给自己单独算一个"整颗留在轨道上方"的锚点。
 * compact 的轨道底边落在触摸区下沿，也就是下方入口条（"观看完整短剧"/"选集"）的顶边，
 * 视频与入口条都不加偏移。圆点直径比轨道高得多，同心的下半必然探进入口条顶部——
 * 那是设计结果而不是裁切：调用方负责把进度条画在入口条之上（见 `VerticalBottomChrome`），
 * 圆点下半既不被轨道也不被入口条盖住，触摸区仍是不变的整块 24dp。
 * 非 compact 沿用触摸区正中，普通播放器的轨道与圆点仍落在同一水平。
 */
internal fun progressCenterLineY(compact: Boolean, height: Float, trackHeight: Float): Float =
    if (compact) height - trackHeight / 2f else height / 2f

/** 触点 → 毫秒：先按 compact 定两端让出的半径，再交给 [msAt] 换算。 */
private fun PointerInputScope.seekMsAt(x: Float, durationMs: Long, compact: Boolean): Long {
    val inset = if (compact) 0f else ThumbSize.toPx() / 2f
    return msAt(x, size.width.toFloat(), inset, durationMs)
}

/**
 * 触点 → 毫秒。原生非 compact 两端各留一个圆点半径，圆点贴边时进度正好是 0 / 100%；
 * compact 的轨道铺满 viewport，直接用 0..width 映射，与绘制口径一致。
 */
private fun msAt(x: Float, width: Float, inset: Float, durationMs: Long): Long {
    val fraction = trackFraction(x, width, inset) ?: return 0L
    return (durationMs * fraction).toLong()
}

/** 触点在可播跨度上的比例；未测量或跨度窄于两颗圆点时没有可播区间，返回 null。 */
private fun trackFraction(x: Float, width: Float, inset: Float): Float? {
    val playable = width - inset * 2f
    if (width <= 0f || playable <= 0f) return null
    return ((x - inset) / playable).coerceIn(0f, 1f)
}
