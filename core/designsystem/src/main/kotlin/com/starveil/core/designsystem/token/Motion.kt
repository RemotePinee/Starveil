package com.starveil.core.designsystem.token

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * 动效令牌。禁止页面自定长时长（里 tween(700) 之类随处可见）。
 */
object Motion {
    const val ShortMs = 120
    const val MediumMs = 200
    const val LongMs = 320

    /** 底栏 tab 切换：照优酷 `tab_fragment_push_right_*` 的 250ms。 */
    const val TabSwitchMs = 300

    /** 骨架屏呼吸：单程时长，配合 Reverse 一个来回约 1.8s。 */
    const val BreathMs = 900

    val standard: Easing = FastOutSlowInEasing

    /** 底栏与二级页推拉：M3 emphasized（cubic-bezier(0.2, 0, 0, 1)），起步快、落点稳。 */
    val emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    val short = tween<Float>(durationMillis = ShortMs, easing = standard)
    val medium = tween<Float>(durationMillis = MediumMs, easing = standard)
    val long = tween<Float>(durationMillis = LongMs, easing = standard)

    /** 拖拽接近边界时加重，普通区保留跟手感。 */
    const val SheetDragGain = 0.9f
    const val SheetBoundaryGain = 0.35f
    const val SheetResistanceZone = 0.18f
    const val SheetHalfOffset = 0.5f
    const val SheetVelocityThreshold = 600f

    /** DampedSheet 弹簧回弹刚度（spring stiffness）。 */
    const val SpringStiffness = 400f

    /** DampedSheet 弹簧阻尼比（damping ratio）。 */
    const val SpringDampingRatio = 0.85f

    /** DampedSheet scrim 最大不透明度（0.0-1.0）。 */
    const val SheetScrimAlpha = 0.32f
}
