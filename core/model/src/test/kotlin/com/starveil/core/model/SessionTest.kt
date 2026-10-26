package com.starveil.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话还原。规则来自旧 。
 */
class SessionTest {

    @Test
    fun `all seven cookies are mapped`() {
        val session = Session.fromCookies(
            mapOf(
                "user_id" to "42",
                "user_name" to "demo",
                "user_nick_name" to "小样",
                "group_id" to "3",
                "group_name" to "VIP",
                "user_check" to "abc123",
                "user_portrait" to "https://img.example.com/p.png",
            ),
        )

        assertEquals("42", session.userId)
        assertEquals("demo", session.userName)
        assertEquals("小样", session.nickName)
        assertEquals("3", session.groupId)
        assertEquals("VIP", session.groupName)
        assertEquals("abc123", session.userCheck)
        assertEquals("https://img.example.com/p.png", session.portrait)
        assertTrue(session.isLoggedIn)
        assertTrue(session.hasSessionCheck)
    }

    @Test
    fun `suffixed cookie names are matched by prefix`() {
        // 后端会给同名 cookie 加后缀，精确匹配会漏
        val session = Session.fromCookies(mapOf("user_id_v2" to "77", "user_check_v2" to "xyz"))

        assertEquals("77", session.userId)
        assertEquals("xyz", session.userCheck)
    }

    @Test
    fun `most specific suffix wins when several match`() {
        val session = Session.fromCookies(mapOf("user_id" to "old", "user_id_v2" to "new"))

        assertEquals("new", session.userId)
    }

    @Test
    fun `missing user id means not logged in`() {
        val session = Session.fromCookies(mapOf("user_check" to "abc"))

        assertFalse(session.isLoggedIn)
        assertEquals("", session.userId)
    }

    @Test
    fun `session without check string is still logged in locally but flagged`() {
        val session = Session.fromCookies(mapOf("user_id" to "42"))

        assertTrue(session.isLoggedIn)
        assertFalse(session.hasSessionCheck)
    }

    @Test
    fun `values are trimmed and blanks normalised`() {
        val session = Session.fromCookies(mapOf("user_id" to "  42  "))

        assertEquals("42", session.userId)
    }

    @Test
    fun `round trip through cookie map preserves session`() {
        val original = Session.fromCookies(
            mapOf("user_id" to "42", "user_name" to "demo", "user_check" to "abc"),
        )

        val restored = Session.fromCookies(original.toCookieMap())

        assertEquals(original, restored)
    }
}
