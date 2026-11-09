package com.starveil.app.navigation

import org.junit.Assert.assertTrue
import org.junit.Test

/** 底栏会员栏的登录标记：漏了它，未登录点会员会先滑进空页再被弹回来。 */
class BottomTabSpecTest {

    @Test
    fun `default member tab demands an account`() {
        val member = bottomTabSpecs(config = null).single { it.id == "member" }

        assertTrue(member.requiresAccount)
    }

    @Test
    fun `other tabs stay open to guests`() {
        val guests = bottomTabSpecs(config = null).filter { it.id != "member" }

        assertTrue(guests.none { it.requiresAccount })
    }
}
