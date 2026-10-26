package com.starveil.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 继续观看的换算规则：0 起下标 → 1 起集号，已看秒数 → 百分比。
 *
 * 百分比按名义单集时长估算（后端不下发时长），这里守的是边界：
 * 负值 / 0 / 刚过一秒 / 远超一集，都不能显示成 101% 或负数。
 */
class ContinueWatchingRulesTest {

    @Test
    fun `episode index is zero based so display number is plus one`() {
        assertEquals(1, item(episodeIndex = 0).episodeNumber)
        assertEquals(3, item(episodeIndex = 2).episodeNumber)
    }

    @Test
    fun `zero and negative progress show zero percent`() {
        assertEquals(0, ContinueWatchingRules.percent(0))
        assertEquals(0, ContinueWatchingRules.percent(-30))
    }

    @Test
    fun `one second in is rounded up to one percent`() {
        assertEquals(1, ContinueWatchingRules.percent(1))
    }

    @Test
    fun `half of a nominal episode is fifty percent`() {
        assertEquals(50, ContinueWatchingRules.percent(ContinueWatchingRules.NOMINAL_EPISODE_SECONDS / 2))
    }

    @Test
    fun `progress beyond one episode is capped at one hundred`() {
        assertEquals(100, ContinueWatchingRules.percent(ContinueWatchingRules.NOMINAL_EPISODE_SECONDS * 3))
    }

    private fun item(episodeIndex: Int) = ContinueWatching(
        vodId = 101,
        vodName = "示例影片",
        vodPic = null,
        sourceIndex = 0,
        episodeIndex = episodeIndex,
        progressSec = 600,
        updatedAt = 1760000000,
    )
}
