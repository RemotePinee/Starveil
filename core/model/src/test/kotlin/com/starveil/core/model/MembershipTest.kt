package com.starveil.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会员判定是"我的"页会员卡与会员页 Hero 共用的规则函数，
 * 边界（会话未读 / 未登录 / 有 end_time 但不是会员 / 会员无到期时间）必须逐条钉住。
 */
class MembershipTest {

    @Test
    fun `pending while the session has not been read`() {
        // 首帧：本地会话还没 restore 完，不能先断言"未登录"
        assertEquals(MembershipStatus.Pending, null.toMembershipStatus(isLoggedIn = null))
        assertEquals("——", MembershipStatus.Pending.label)
        assertFalse(MembershipStatus.Pending.isMember)
    }

    @Test
    fun `guest when there is no session`() {
        assertEquals(MembershipStatus.Guest, null.toMembershipStatus(isLoggedIn = false))
        assertEquals("登录后查看会员状态", MembershipStatus.Guest.label)
    }

    @Test
    fun `logged in without a profile counts as not subscribed`() {
        // 资料没到或读取失败：先给稳定文案，别再闪一次"登录后查看会员状态"
        assertEquals(MembershipStatus.NotSubscribed, null.toMembershipStatus(isLoggedIn = true))
        assertEquals("尚未开通会员，开通后可畅看会员专区", MembershipStatus.NotSubscribed.label)
    }

    @Test
    fun `end time alone is not a membership`() {
        // 回归：后台新建的游客账号 user_end_time 有值，但 is_vip 是 false
        val profile = profile(isVip = false, memberEndTime = FUTURE_END)

        assertEquals(MembershipStatus.NotSubscribed, profile.toMembershipStatus(isLoggedIn = true))
        assertFalse(profile.toMembershipStatus(isLoggedIn = true).isMember)
    }

    @Test
    fun `vip with an end time keeps the date`() {
        val status = profile(isVip = true, memberEndTime = FUTURE_END).toMembershipStatus(true)

        assertTrue(status.isMember)
        assertTrue(status.label.startsWith("会员有效期至 "))
    }

    @Test
    fun `vip without an end time does not invent a date`() {
        val status = profile(isVip = true, memberEndTime = 0).toMembershipStatus(true)

        assertEquals(MembershipStatus.ActiveWithoutExpiry, status)
        assertTrue(status.isMember)
        assertEquals("会员有效中", status.label)
    }

    private fun profile(isVip: Boolean, memberEndTime: Long): UserProfile = UserProfile(
        userId = USER_ID,
        userName = "demo",
        nickName = "演示用户",
        portrait = null,
        groupId = 3,
        groupName = "黄金会员",
        level = "3",
        points = 0,
        userPoints = 0,
        gold = "0",
        status = 1,
        memberEndTime = memberEndTime,
        viewCount = null,
        viewSeconds = 0,
        isVip = isVip,
        isAgents = "0",
    )

    private companion object {
        const val USER_ID = 42
        const val FUTURE_END = 1_799_999_999L
    }
}
