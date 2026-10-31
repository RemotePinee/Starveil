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
        val vip = TrySeeGate(access = TrySeeAccess.NEED_VIP, trySeeSeconds = 360)
        assertTrue(vip.canUpgrade)
        assertFalse(vip.canRedeemPoints)
        assertFalse(vip.canWatchAd)
        assertEquals(0, vip.pointsPrice)

        val pay = TrySeeGate(access = TrySeeAccess.NEED_PAY, trySeeSeconds = 360)
        assertFalse(pay.canUpgrade)
        assertTrue(pay.canRedeemPoints)
    }

    @Test
    fun `ten minute window comes from trysee seconds`() {
        // 服务端按秒下发（cdn_trysee_minutes × 60）：600 = 前 10 分钟可看
        assertFalse(TrySeeRules.isExceeded(positionMs = 0, trySeeSeconds = 600))
        assertFalse(TrySeeRules.isExceeded(positionMs = 599_000, trySeeSeconds = 600))
        assertFalse(TrySeeRules.isExceeded(positionMs = 600_000, trySeeSeconds = 600))
        assertTrue(TrySeeRules.isExceeded(positionMs = 600_001, trySeeSeconds = 600))
    }

    @Test
    fun `zero seconds means no window at all`() {
        // CDN 没接通 / 没配试看 / 已解锁，服务端都发 0：需解锁的集一开始就拦
        assertTrue(TrySeeRules.isExceeded(positionMs = 0, trySeeSeconds = 0))
        assertTrue(TrySeeRules.isExceeded(positionMs = 10_000_000, trySeeSeconds = -1))
    }

    @Test
    fun `sentinel 888 is just a long window after the single switch`() {
        // 888 不再有"直接拦截"的语义，现在只是一个普通的 888 秒
        assertFalse(TrySeeRules.isExceeded(positionMs = 0, trySeeSeconds = 888))
        assertFalse(TrySeeRules.isExceeded(positionMs = 888_000, trySeeSeconds = 888))
    }
}
