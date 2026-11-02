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
        scrollState.scrollTo(target)
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
    val selected = span ?: return null
    val leftPx = with(density) { selected.left.toPx() }
    val rightPx = leftPx + with(density) { selected.width.toPx() }
    val windowStart = scrollState.value.toFloat()
    val windowEnd = windowStart + scrollState.viewportSize.toFloat()
    val alreadyVisible = leftPx >= windowStart && rightPx <= windowEnd
    val target = (leftPx - with(density) { leadingInset.toPx() }).toInt()
    return if (alreadyVisible) null else target.coerceIn(0, scrollState.maxValue)
}
