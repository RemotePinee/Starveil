package com.starveil.feature.user

import app.cash.turbine.test
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 个人资料页。四条：加载成功、加载失败、无会话（未登录）、退出登录清会话。
 *
 * 数值断言覆盖"影豆 / 积分 / 金币"三个余额的取法，
 * 其中金币是字符串，断言里保留后端给的小数点。
 */
class ProfileViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `profile loads and exposes the account numbers`() = runTest {
        val users = FakeUserRepository()
        val viewModel = ProfileViewModel(users, FakeAuthRepository(restored = demoSession()))

        val state = viewModel.state.value
        assertFalse(state.needsLogin)
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals("示例昵称", state.displayName)
        assertEquals("默认会员", state.groupLabel)
        assertEquals(listOf("0", "10", "29.00"), state.stats.map { it.value })
        assertEquals(1, users.loadCalls)
    }

    @Test
    fun `profile failure shows the error state`() = runTest {
        val users = FakeUserRepository(result = AppResult.Failure(AppError.Network(cause = null)))
        val viewModel = ProfileViewModel(users, FakeAuthRepository(restored = demoSession()))

        val state = viewModel.state.value
        assertNotNull(state.errorMessage)
        assertNull(state.profile)
        assertFalse(state.isLoading)
        assertTrue(state.isLoggedIn)
    }

    @Test
    fun `missing session shows the login prompt without loading the profile`() = runTest {
        val users = FakeUserRepository()
        val viewModel = ProfileViewModel(users, FakeAuthRepository(restored = null))

        val state = viewModel.state.value
        assertTrue(state.needsLogin)
        assertNull(state.profile)
        assertEquals(0, users.loadCalls)
    }

    @Test
    fun `logout clears the session and emits signed out`() = runTest {
        val auth = FakeAuthRepository(restored = demoSession())
        val viewModel = ProfileViewModel(FakeUserRepository(), auth)

        viewModel.events.test {
            viewModel.logout()
            assertEquals(ProfileEvent.SignedOut, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(1, auth.logoutCalls)
        assertNull(viewModel.state.value.profile)
        assertTrue(viewModel.state.value.needsLogin)
        assertFalse(viewModel.state.value.isLoggingOut)
    }
}
