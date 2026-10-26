package com.starveil.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 集数格文案：中文集名去掉「第/集」、空白，去空后用 nid 兜底。 */
class EpisodeLabelTest {

    @Test
    fun `strips chinese episode affixes`() {
        assertEquals("45", episodeChipLabel("第45集", 45))
        assertEquals("145", episodeChipLabel("第145集", 145))
        assertEquals("3", episodeChipLabel("第 3 集", 3))
        assertEquals("12", episodeChipLabel(" 第12集 ", 12))
    }

    @Test
    fun `keeps titles that are not episode numbers`() {
        assertEquals("正片", episodeChipLabel("正片", 1))
        assertEquals("预告", episodeChipLabel("预告", 2))
        assertEquals("45", episodeChipLabel("45", 45))
    }

    @Test
    fun `falls back to the backend number when title is empty`() {
        assertEquals("7", episodeChipLabel("", 7))
        assertEquals("7", episodeChipLabel("   ", 7))
        assertEquals("7", episodeChipLabel("第集", 7))
    }
}

/** 两行展示文本：期号优先，其次日期，最后才是文本兜底。 */
class EpisodeTextTest {

    @Test
    fun `rank wins over everything`() {
        assertEquals(EpisodeText("第5期", ""), episodeText("第5期", 5))
        assertEquals(EpisodeText("第5集", ""), episodeText("第5集", 5))
    }

    @Test
    fun `rank keeps the date as secondary line`() {
        assertEquals(EpisodeText("第5期", "10-24"), episodeText("第5期 2026-10-24", 5))
    }

    @Test
    fun `date becomes month-day with number as secondary`() {
        assertEquals(EpisodeText("10-24", "第3集"), episodeText("2026-10-24", 3))
        assertEquals(EpisodeText("09-01", "第1集"), episodeText("2026/9/1", 1))
    }

    @Test
    fun `compact year-less date string becomes month-day`() {
        // 综艺常用 8 位纯数字当标题，没有分隔符；以前会落到文本兜底显示成 `…260119`
        assertEquals(EpisodeText("01-19", "第7集"), episodeText("20260119", 7))
        assertEquals(EpisodeText("08-16", "第24集"), episodeText("20260816", 24))
        // 不合法月份不抢纯数字分支
        assertEquals(EpisodeText("1345"), episodeText("1345", 13))
    }

    @Test
    fun `plain numbers stay narrow`() {
        assertEquals(EpisodeText("12"), episodeText("12", 12))
        assertEquals("12", episodeChipLabel("12", 12))
    }

    @Test
    fun `text titles go wide and keep the tail`() {
        val label = episodeText("国语原声版超长标题", 1)
        assertTrue(label.wide)
        assertTrue(label.primary.startsWith("…"))
        assertTrue(label.primary.endsWith("原声版超长标题".takeLast(6)))
    }

    @Test
    fun `layout is wide when any episode is a text title`() {
        val numbered = listOf(play("第1集", 1), play("第2集", 2))
        assertFalse(episodeLayoutWide(numbered))
        assertTrue(episodeLayoutWide(numbered + play("国语原声版", 3)))
    }

    private fun play(title: String, number: Int) = PlayUrl(title = title, url = "x", episodeNumber = number)
}
