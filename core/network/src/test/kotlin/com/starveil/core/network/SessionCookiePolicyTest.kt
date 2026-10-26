package com.starveil.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录态 cookie 只发给需要的接口。
 */
class SessionCookiePolicyTest {

    @Test
    fun `paths in the whitelist require session`() {
        assertTrue(SessionCookiePolicy.requiresSession(ApiPaths.PAY))
        assertTrue(SessionCookiePolicy.requiresSession(ApiPaths.USER_INFO))
        assertTrue(SessionCookiePolicy.requiresSession(ApiPaths.VOD_DETAIL))
        assertTrue(SessionCookiePolicy.requiresSession(ApiPaths.FAVS))
    }

    @Test
    fun `public endpoints stay cookie free`() {
        assertFalse(SessionCookiePolicy.requiresSession(ApiPaths.VOD_LIST))
        assertFalse(SessionCookiePolicy.requiresSession(ApiPaths.STARTUP))
        assertFalse(SessionCookiePolicy.requiresSession(ApiPaths.LOGIN))
    }

    @Test
    fun `prefix and query string are normalised away`() {
        assertTrue(SessionCookiePolicy.requiresSession("/icciu_api.php/v1.user/pay?type=1"))
        assertTrue(SessionCookiePolicy.requiresSession("v1.user/pay"))
    }

    @Test
    fun `blank path never requires session`() {
        assertFalse(SessionCookiePolicy.requiresSession(null))
        assertFalse(SessionCookiePolicy.requiresSession(""))
        assertFalse(SessionCookiePolicy.requiresSession("   "))
    }

    @Test
    fun `whitelist keeps the legacy path set explicit`() {
        assertEquals(22, SessionCookiePolicy.loginRequiredPaths().size)
    }
}
