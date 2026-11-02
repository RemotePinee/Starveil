package com.starveil.feature.shortplay

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.starveil.core.model.ShortPlayType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 顶部分类下划线的连续插值：横滑时它在两页之间跟手移动，不能等松手才跳。
 *
 * 下划线宽度是固定值（[UNDERLINE_WIDTH]），不跟分类文字伸缩，所以位置断言的是
 * 「两侧各自居中后的左边界」之间的连续插值，宽度断言在任何位置上都是同一个值。
 */
class ShortPlayUnderlineTest {
    private val categories = listOf(
        ShortPlayType(id = 1, name = "全部"),
        ShortPlayType(id = 2, name = "甜宠"),
        ShortPlayType(id = 3, name = "悬疑"),
    )

    // 三个分类的文字宽度故意不同（40 / 60 / 80）：下划线宽度不受它们影响。
    private val spans = mapOf(
        1 to ChipSpan(left = 0.dp, width = 40.dp),
        2 to ChipSpan(left = 60.dp, width = 60.dp),
        3 to ChipSpan(left = 140.dp, width = 80.dp),
    )

    /** 期望位置：在文字上居中——`left + (文字宽 - 下划线宽) / 2`，不是文字左边界。 */
    private fun centeredLeft(span: ChipSpan): Dp = span.left + (span.width - UNDERLINE_WIDTH) / 2

    @Test
    fun `settled on a page centers the underline on that chip`() {
        val span = interpolateChipSpan(categories, spans, 1f)
        assertEquals(centeredLeft(spans.getValue(2)), span?.left)
        assertEquals(UNDERLINE_WIDTH, span?.width)
    }

    @Test
    fun `halfway between two pages interpolates position only`() {
        val span = interpolateChipSpan(categories, spans, 1.5f)
        val from = centeredLeft(spans.getValue(2))
        val to = centeredLeft(spans.getValue(3))
        // 位置取两侧的中点；宽度不插值——两个分类文字宽度不同，线也一样长
        assertEquals((from + to) / 2, span?.left)
        assertEquals(UNDERLINE_WIDTH, span?.width)
    }

    @Test
    fun `width stays fixed across chips of different text widths`() {
        val first = interpolateChipSpan(categories, spans, 0f)
        val third = interpolateChipSpan(categories, spans, 2f)
        assertEquals(UNDERLINE_WIDTH, first?.width)
        assertEquals(UNDERLINE_WIDTH, third?.width)
        assertEquals(first?.width, third?.width)
    }

    @Test
    fun `overscroll past either end stays on the end chip`() {
        val start = interpolateChipSpan(categories, spans, -0.4f)
        val end = interpolateChipSpan(categories, spans, 2.4f)
        assertEquals(centeredLeft(spans.getValue(1)), start?.left)
        assertEquals(centeredLeft(spans.getValue(3)), end?.left)
    }

    @Test
    fun `missing span on one side falls back to the other`() {
        val partial = mapOf(2 to ChipSpan(left = 60.dp, width = 60.dp))
        val span = interpolateChipSpan(categories, partial, 0.5f)
        assertEquals(centeredLeft(spans.getValue(2)), span?.left)
        assertEquals(UNDERLINE_WIDTH, span?.width)
    }

    @Test
    fun `chip narrower than the underline never gets a negative offset`() {
        // 文字只有 8dp，比下划线还窄：居中是负偏移，夹回文字左缘，不让线滑出这一项
        val narrow = mapOf(1 to ChipSpan(left = 100.dp, width = 8.dp))
        val span = interpolateChipSpan(categories, narrow, 0f)
        assertEquals(100.dp, span?.left)
        assertEquals(UNDERLINE_WIDTH, span?.width)
    }

    @Test
    fun `empty categories has no underline`() {
        assertNull(interpolateChipSpan(emptyList(), spans, 0.5f))
    }
}
