package com.starveil.core.designsystem.component

import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.Dp

/**
 * DampedSheet 内容作用域：暴露动态高度与偏移，供宿主排版与交互。
 *
 * [maxHeight] 是内容区可用最大高度（已扣状态栏、IME/导航栏），Sheet measure 始终固定此高度。
 * [visibleHeight] 随拖拽偏移实时变化，供 body 宿主裁剪可见内容（ViewModel 可用它判断是否展开）。
 * [offsetPx] 当前纵向偏移（正值向下拉，0 为全展开，窗口底边为最大值）。
 * [windowHeightPx] 窗口总高度（物理像素），供外部计算百分比或锚点。
 */
@Stable
interface DampedSheetScope {
    /** 内容区最大可用高度（扣除状态栏、IME/导航栏后的安全高度）。 */
    val maxHeight: Dp

    /** 当前实际可见高度（随拖拽偏移变化）。 */
    val visibleHeight: Dp

    /** 当前纵向偏移（px，正值向下，0 为全展开）。 */
    val offsetPx: Float

    /** 窗口总高度（px）。 */
    val windowHeightPx: Int
}
