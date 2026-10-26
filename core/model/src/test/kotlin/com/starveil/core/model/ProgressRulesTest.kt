package com.starveil.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度上报口径。每 30 秒累加 60 秒（2 倍虚增），新版按真实间隔。
 */
class ProgressRulesTest {

    @Test
    fun `first report happens immediately`() {
        assertTrue(ProgressRules.shouldReport(lastReportAtMs = null, nowMs = 1_000))
    }

    @Test
    fun `report waits for one full interval`() {
        assertFalse(ProgressRules.shouldReport(lastReportAtMs = 1_000, nowMs = 30_999))
        assertTrue(ProgressRules.shouldReport(lastReportAtMs = 1_000, nowMs = 31_000))
    }

    @Test
    fun `reported seconds equal the real interval`() {
        assertEquals(30, ProgressRules.secondsToReport(30_000))
        assertEquals(0, ProgressRules.secondsToReport(999))
        assertEquals(0, ProgressRules.secondsToReport(-5_000))
    }

    @Test
    fun `timer constants match the legacy values`() {
        assertEquals(30_000L, ProgressRules.REPORT_INTERVAL_MS)
        assertEquals(10_000L, ProgressRules.DANMU_PREFETCH_WINDOW_MS)
        assertEquals(30_000L, ProgressRules.DANMU_EMPTY_ADVANCE_MS)
        assertEquals(8_000L, PlaybackMachine.LINE_TIMEOUT_MS)
    }
}
