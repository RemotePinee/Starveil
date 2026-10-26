package com.starveil.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹幕排布（纯函数）。
 *
 * 三条生产约束，缺一条体验就会崩：
 * 1. **时间不改**：出场时间等于弹幕自己的时间，弹幕必须对上画面；
 * 2. **同轨不追尾**：占用时长按真实文字宽度算，长弹幕要占更久；
 * 3. **用户优先**：官方弹幕再多，也不能把本站用户弹幕挤掉。
 */
class DanmuLayoutTest {

    /** 每个字符 20px、速度 200px/s、空隙 0：占用时长刚好是"字数 × 100ms"。 */
    private fun config(lanes: Int = 3) = DanmuLayoutConfig(
        laneCount = lanes,
        speedPxPerSec = 200f,
        gapPx = 0f,
        measureWidth = { text -> text.length * 20f },
    )

    @Test
    fun `same moment danmu fill distinct lanes then get dropped`() {
        val items = List(6) { index -> item(text = "同秒$index", at = 0L) }

        val laid = layoutDanmu(items, config())

        // 同一时刻三条轨道各容一条，其余丢弃（不改时间、不硬挤）
        assertEquals(3, laid.size)
        assertEquals(setOf(0, 1, 2), laid.map { it.lane }.toSet())
        assertTrue(laid.all { it.atTimeMs == 0L })
    }

    @Test
    fun `long text blocks the next one on the same lane`() {
        // 40 字 = 800px = 4 秒，这条长弹幕占满唯一轨道到 4000ms
        val long = item(text = "长".repeat(40), at = 0L)
        val next = item(text = "短", at = 1_000L)

        val laid = layoutDanmu(listOf(long, next), config(lanes = 1))

        // 1 秒时轨道还被长弹幕占着，短的这条这一轮不排（固定 2 秒间隔会追尾）
        assertEquals(1, laid.size)
        assertEquals("长".repeat(40), laid.single().text)
    }

    @Test
    fun `user danmu is placed before official flood`() {
        val official = List(10) { index ->
            item(text = "官方$index", at = 0L, priority = DanmuItem.PRIORITY_OFFICIAL)
        }
        val user = listOf(item(text = "本站弹幕", at = 0L, priority = DanmuItem.PRIORITY_USER))

        val laid = layoutDanmu(official + user, config())

        // 用户弹幕一定在里面，官方只能吃剩下的空位
        assertTrue(laid.any { it.text == "本站弹幕" })
        assertEquals(3, laid.size)
    }

    @Test
    fun `output stays ordered by time for binary search`() {
        val items = listOf(
            item(text = "三", at = 3_000L),
            item(text = "一", at = 1_000L),
            item(text = "二", at = 2_000L),
        )

        val laid = layoutDanmu(items, config())

        assertEquals(listOf(1_000L, 2_000L, 3_000L), laid.map { it.atTimeMs })
    }

    @Test
    fun `visible range only covers danmu still on screen`() {
        val laid = listOf(
            LaidDanmu(text = "旧", atTimeMs = 0L, lane = 0),
            LaidDanmu(text = "中", atTimeMs = 10_000L, lane = 1),
            LaidDanmu(text = "新", atTimeMs = 20_000L, lane = 2),
        )

        val range = visibleRange(laid, positionMs = 15_000L, travelMs = 7_000L)

        // 只包含仍在飞行中的"中"："旧"已飘出去，"新"还没到
        assertEquals(1, range.first)
        assertEquals(1, range.last)
    }

    private fun item(
        text: String,
        at: Long,
        priority: Int = DanmuItem.PRIORITY_USER,
    ): DanmuItem = DanmuItem(text = text, atTimeMs = at, priority = priority)
}
