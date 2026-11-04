package com.starveil.feature.shortplay

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp

/**
 * 分类条首帧的滚动落点：同一个组件两种页面，按 [centered] 分叉。
 *
 * - 剧场页（`centered = true`）：从正中起步。分类多到一屏放不下时，从第一个开始等于整排贴着左边，
 *   右边的淡出提示看着像内容被截断；从正中起步两头各露半截，用户才知道两边都能滑。
 * - 分类页（`centered = false`）：落到当前选中项。两行首项都是虚拟的「全部」，进页面就该看到
 *   选中的那一项，而不是一条从中间开始的分类条；选中项已经完整在视口内则一步不动，免得首帧跳一下。
 *
 * 只定位一次：`maxValue` 要等首帧布局之后才有值，为 0 时不动作；之后用户拖到哪儿就停在哪儿。
 */
@Composable
internal fun ShortPlayCategoryRowScroll(
    scrollState: ScrollState,
    /** 分类列表：换了一份列表就是新的一条，重新定位一次。 */
    contentKey: Any,
    /** 默认居中（剧场页）；分类页传 `false`，改落到选中项。 */
    centered: Boolean = true,
    /** 选中项在滚动内容里的横向范围，由分类项布局后回传；为空时等它回传了再定位。 */
    selectedSpan: ChipSpan?,
    /** 行首留白：选中项落到这条线上，与行内第一项同一条左基线。 */
    leadingInset: Dp,
) {
    val density = LocalDensity.current
    var placed by remember(contentKey) { mutableStateOf(false) }
    LaunchedEffect(contentKey, scrollState.maxValue, centered, selectedSpan) {
        if (placed || scrollState.maxValue <= 0) return@LaunchedEffect
        val target = if (centered) {
            scrollState.maxValue / 2
        } else {
            selectedFirstTarget(scrollState, selectedSpan, leadingInset, density) ?: return@LaunchedEffect
        }
        // 用 animate 而不是 scrollTo：切分类时 contentKey 变了会重定位一次，
        // 瞬跳就是用户看到的"药丸闪一下"。滑动把这次重定位变成连续运动。
        scrollState.animateScrollTo(target)
        placed = true
    }
}

/**
 * 分类页首帧该滚到哪儿：选中项完整落在视口内返回 `null`（不动），否则返回把它拉回行首留白的滚动量。
 *
 * 跨度里的左边界就是分类文字的左边界（`reportChipSpan` 已扣掉分类项的内边距），滚到这里，
 * 选中项的文字正好压在行首留白那条竖线上——和它同一行的其它项一样，从这条线往右读。
 */
private fun selectedFirstTarget(
    scrollState: ScrollState,
    span: ChipSpan?,
    leadingInset: Dp,
    density: Density,
): Int? {
    // 单出口：span 缺失与"已经完整可见"都折成 null，末尾统一返回。
    // 三个提前 return 会撞 detekt 的 ReturnCount（上限 2）。
    val offset = span?.let { selected ->
        val leftPx = with(density) { selected.left.toPx() }
        val rightPx = leftPx + with(density) { selected.width.toPx() }
        val windowStart = scrollState.value.toFloat()
        val windowEnd = windowStart + scrollState.viewportSize.toFloat()
        val insetPx = with(density) { leadingInset.toPx() }
        // 只在「看不到」时做最小滚动，不再一律拉回行首：
        // 选最后一项时拉回行首会把左边两项整块推出屏幕，用户看到的就是"被挤位移"。
        when {
            rightPx > windowEnd -> rightPx - windowEnd + insetPx
            leftPx < windowStart -> leftPx - insetPx
            else -> null
        }
    }
    return offset?.toInt()?.coerceIn(0, scrollState.maxValue)
}
