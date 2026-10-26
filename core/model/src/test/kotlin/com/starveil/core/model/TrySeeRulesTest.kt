package com.starveil.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 试看闸门规则。边界对齐 , 1029-1046`。
 */
class TrySeeRulesTest {

    @Test
    fun `status zero means free`() {
        assertEquals(TrySeeAccess.FREE, TrySeeRules.access(status = 0, freeCount = 0))
    }

    @Test
    fun `remaining free count overrides vip requirement`() {
        assertEquals(TrySeeAccess.FREE, TrySeeRules.access(status = 1, freeCount = 2))
    }

    @Test
    fun `vip and pay statuses are distinguished`() {
        assertEquals(TrySeeAccess.NEED_VIP, TrySeeRules.access(status = 1, freeCount = 0))
        assertEquals(TrySeeAccess.NEED_PAY, TrySeeRules.access(status = 2, freeCount = 0))
    }

    @Test
    fun `unknown status falls back to free like the legacy else branch`() {
        assertEquals(TrySeeAccess.FREE, TrySeeRules.access(status = 99, freeCount = 0))
    }

    @Test
    fun `gate defaults keep the legacy single exit`() {
        // 老后端不下发能力位时，客户端行为必须和改造前一致
        val vip = TrySeeGate(access = TrySeeAccess.NEED_VIP, trySeeMinutes = 6)
        assertTrue(vip.canUpgrade)
        assertFalse(vip.canRedeemPoints)
        assertFalse(vip.canWatchAd)
        assertEquals(0, vip.pointsPrice)

        val pay = TrySeeGate(access = TrySeeAccess.NEED_PAY, trySeeMinutes = 6)
        assertFalse(pay.canUpgrade)
        assertTrue(pay.canRedeemPoints)
    }

    @Test
    fun `position beyond trysee minutes is blocked`() {
        assertTrue(TrySeeRules.isExceeded(positionMs = 6 * 60_000L + 1, trySeeMinutes = 6))
        assertFalse(TrySeeRules.isExceeded(positionMs = 6 * 60_000L, trySeeMinutes = 6))
    }

    @Test
    fun `sentinel 888 blocks immediately`() {
        assertTrue(TrySeeRules.isExceeded(positionMs = 0, trySeeMinutes = 888))
    }

    @Test
    fun `zero or negative minutes never block`() {
        assertFalse(TrySeeRules.isExceeded(positionMs = 10_000_000, trySeeMinutes = 0))
        assertFalse(TrySeeRules.isExceeded(positionMs = 10_000_000, trySeeMinutes = -1))
    }
}
