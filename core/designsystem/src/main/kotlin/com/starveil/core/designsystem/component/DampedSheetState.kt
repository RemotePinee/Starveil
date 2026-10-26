package com.starveil.core.designsystem.component

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.starveil.core.designsystem.token.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

internal enum class SheetAnchor { Expanded, Half, Hidden }

/** 拖动与动画共用同一偏移；新手势取消旧动画，旧关闭动画不会迟到关闭新面板。 */
internal class DampedSheetState {
    var maxHeightPx by mutableIntStateOf(0)
        private set
    var windowHeightPx by mutableIntStateOf(0)
        private set
    var offsetPx by mutableFloatStateOf(0f)
        private set
    private var target = SheetAnchor.Half
    private var animation: Job? = null
    private var initialized = false
        private set
    val isClosing: Boolean get() = target == SheetAnchor.Hidden && initialized

    val draggableState = DraggableState { delta -> dragBy(delta) }

    /** 尺寸变化时保留关闭动画；取消它会让全透明 Dialog 永久留在组合中。 */
    fun updateBounds(maxPx: Int, windowPx: Int): Boolean {
        val first = !initialized
        maxHeightPx = maxPx.coerceAtLeast(0)
        windowHeightPx = windowPx
        if (!isClosing) {
            stop()
            offsetPx = anchorOffset(if (first) SheetAnchor.Hidden else target)
        }
        initialized = true
        return first
    }

    fun stop() {
        animation?.cancel()
        animation = null
    }

    fun dragBy(delta: Float) {
        stop()
        offsetPx = sheetDragOffset(offsetPx, delta, maxHeightPx.toFloat())
    }

    fun settle(scope: CoroutineScope, velocity: Float, onDismiss: () -> Unit) {
        val anchor = sheetTarget(offsetPx, velocity, maxHeightPx.toFloat())
        animateTo(scope, anchor, velocity, onDismiss)
    }

    fun animateTo(
        scope: CoroutineScope,
        anchor: SheetAnchor,
        velocity: Float = 0f,
        onDismiss: () -> Unit = {},
    ) {
        stop()
        target = anchor
        animation = scope.launch {
            animate(
                initialValue = offsetPx,
                targetValue = anchorOffset(anchor),
                initialVelocity = velocity,
                animationSpec = sheetAnimationSpec(anchor),
            ) { value, _ -> offsetPx = value.coerceIn(0f, maxHeightPx.toFloat()) }
            if (anchor == SheetAnchor.Hidden) onDismiss()
        }
    }

    private fun anchorOffset(anchor: SheetAnchor): Float = when (anchor) {
        SheetAnchor.Expanded -> 0f
        SheetAnchor.Half -> maxHeightPx * Motion.SheetHalfOffset
        SheetAnchor.Hidden -> maxHeightPx.toFloat()
    }
}

/** 向边界移动时渐增阻力，反向立即释放；硬边界保证上圆角不进入状态栏。 */
internal fun sheetDragOffset(offset: Float, delta: Float, max: Float): Float {
    if (max <= 0f) return 0f
    val remaining = if (delta < 0f) offset else max - offset
    val proximity = (remaining / (max * Motion.SheetResistanceZone)).coerceIn(0f, 1f)
    val gain = Motion.SheetBoundaryGain + (Motion.SheetDragGain - Motion.SheetBoundaryGain) * proximity
    return (offset + delta * gain).coerceIn(0f, max)
}

/** 快甩也只走相邻停靠点：全展开下甩先回半屏，不直接消失。 */
internal fun sheetTarget(offset: Float, velocity: Float, max: Float): SheetAnchor {
    val half = max * Motion.SheetHalfOffset
    return when {
        velocity < -Motion.SheetVelocityThreshold -> {
            if (offset > half + 1f) SheetAnchor.Half else SheetAnchor.Expanded
        }
        velocity > Motion.SheetVelocityThreshold -> {
            if (offset < half - 1f) SheetAnchor.Half else SheetAnchor.Hidden
        }
        offset < half / 2f -> SheetAnchor.Expanded
        abs(offset - half) <= (max - half) / 2f -> SheetAnchor.Half
        else -> SheetAnchor.Hidden
    }
}

internal fun sheetVisibleHeight(maxBodyPx: Int, offsetPx: Float): Int =
    (maxBodyPx - offsetPx).toInt().coerceIn(0, maxBodyPx.coerceAtLeast(0))

/** 关闭用有界退场，不等待弹簧亚像素拖尾；展开与半屏仍保留阻尼回弹。 */
internal fun sheetAnimationSpec(anchor: SheetAnchor): AnimationSpec<Float> =
    if (anchor == SheetAnchor.Hidden) Motion.medium else spring(
        dampingRatio = Motion.SpringDampingRatio,
        stiffness = Motion.SpringStiffness,
    )
