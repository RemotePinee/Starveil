package com.starveil.app.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「我的」页入口的登录判定：这一条错了，用户会点进一个空页或者被无端拦下。 */
class EntryAccessTest {

    @Test
    fun `account only entries are blocked`() {
        listOf("我的收藏", "积分中心", "提现", "设置", "开通会员", "会员续费", "卡密兑换").forEach { label ->
            assertTrue(label, entryRequiresAccount(label))
        }
    }

    @Test
    fun `browsing entries stay open to guests`() {
        listOf("观看记录", "意见反馈", "榜单", "专题", "").forEach { label ->
            assertFalse(label, entryRequiresAccount(label))
        }
    }
}
