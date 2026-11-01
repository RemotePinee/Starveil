package com.starveil.feature.shortplay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * feed 级视频层的滑页位移：拖动全程连续、落页归零、量不出页高时一动不动。
 *
 * 连续性是最容易写错的一条：pager 在拖过一半时把 `currentPage` 换成下一页，
 * 偏移从 +0.5 翻成 -0.5，只看 `currentPageOffsetFraction` 的画面会在屏幕正中跳一屏。
 */
class ShortPlayFeedVideoLayerTest {

    @Test
    fun `dragging to the next page moves the frame along with the leaving page`() {
        // 过半之前：currentPage 还是 0，画面已经上移半屏
        assertEquals(-500f, feedVideoTranslationY(0, 0, 0.5f, ONE_PAGE), 0.001f)
        // 过半之后 currentPage 翻到 1：位置必须接得上
        assertEquals(-500f, feedVideoTranslationY(0, 1, -0.5f, ONE_PAGE), 0.001f)
        // 滑走一屏，画面也跟着走完一屏
        assertEquals(-ONE_PAGE, feedVideoTranslationY(0, 1, 0f, ONE_PAGE), 0.001f)
    }

    @Test
    fun `dragging back to the previous page moves the frame down`() {
        assertEquals(500f, feedVideoTranslationY(1, 1, -0.5f, ONE_PAGE), 0.001f)
        assertEquals(250f, feedVideoTranslationY(1, 0, 0.75f, ONE_PAGE), 0.001f)
    }

    @Test
    fun `the frame sits on the viewport once the page settles`() {
        assertEquals(0f, feedVideoTranslationY(3, 3, 0f, ONE_PAGE), 0.001f)
        // 落页后锚点就是 currentPage：换到第几页都一样归零，不会留着上一次的偏移
        assertEquals(0f, feedVideoTranslationY(7, 7, 0f, ONE_PAGE), 0.001f)
    }

    @Test
    fun `a jump across many pages never drags a stale frame back into view`() {
        // 换分类会 scrollToPage 跳到远处：压到一屏，画面正好在视口外
        assertEquals(-ONE_PAGE, feedVideoTranslationY(0, 5, 0f, ONE_PAGE), 0.001f)
        assertEquals(ONE_PAGE, feedVideoTranslationY(9, 0, 0f, ONE_PAGE), 0.001f)
    }

    @Test
    fun `unknown geometry keeps the frame still`() {
        assertEquals(0f, feedVideoTranslationY(0, 0, 0.5f, 0f), 0.001f)
        assertEquals(0f, feedVideoTranslationY(0, 0, Float.NaN, ONE_PAGE), 0.001f)
        assertEquals(0f, feedVideoTranslationY(0, 0, Float.POSITIVE_INFINITY, ONE_PAGE), 0.001f)
    }

    private companion object {
        /** 一屏高：断言里的位移直接按它换算成"半屏 / 一屏"。 */
        const val ONE_PAGE = 1_000f
    }
}
