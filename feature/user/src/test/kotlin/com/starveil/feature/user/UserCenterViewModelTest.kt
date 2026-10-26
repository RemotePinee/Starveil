package com.starveil.feature.user

import app.cash.turbine.test
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.model.Session
import com.starveil.core.model.UserCenterVariant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 用户中心状态持有者。主线：未登录不请求、已登录填值、资料失败降级、退出清空、刷新重拉。
 *
 * 皮肤只影响 [UserCenterLayouts]，所以这里顺带断言"变体 → 区块列表"没有掉线。
 */
class UserCenterViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `restoring without a session reports signed out`() = runTest {
        val viewModel = UserCenterViewModel(
            authRepository = FakeAuthRepository(restored = null),
            variant = UserCenterVariant.A,
        )

        viewModel.state.test {
            val state = awaitItem()
            assertNull(state.session)
            assertFalse(state.isLoggedIn)
            assertFalse(state.isLoading)
            assertEquals(UserCenterVariant.A, state.variant)
            assertTrue(state.sections.isNotEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `signed out session never requests the profile`() = runTest {
        val users = FakeUserRepository()

        val viewModel = UserCenterViewModel(
            authRepository = FakeAuthRepository(restored = null),
            userRepository = users,
            variant = UserCenterVariant.C,
        )

        val state = viewModel.state.value
        assertNull(state.profile)
        assertEquals(0, users.loadCalls)
        assertEquals("——", state.values.stat("影豆"))
        assertFalse(state.isLoading)
    }

    @Test
    fun `restored session is exposed together with the skin variant`() = runTest {
        val session: Session = demoSession()
        val viewModel = UserCenterViewModel(
            authRepository = FakeAuthRepository(restored = session),
            variant = UserCenterVariant.F,
        )

        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state.isLoggedIn)
            assertEquals("42", state.session?.userId)
            assertEquals("演示用户", state.displayName)
            assertEquals("黄金会员", state.rankLabel)
            assertEquals(UserCenterVariant.F, state.variant)
            assertFalse(state.isLoading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `restored session fills the profile values`() = runTest {
        val viewModel = UserCenterViewModel(
            authRepository = FakeAuthRepository(restored = demoSession()),
            userRepository = FakeUserRepository(result = AppResult.Success(demoProfile())),
            variant = UserCenterVariant.A,
        )

        val state = viewModel.state.value
        // 昵称/会员组以接口为准，比 cookie 里的值更新
        assertEquals("示例昵称", state.displayName)
        assertEquals("默认会员", state.rankLabel)
        assertEquals("3", state.levelLabel)
        assertEquals("默认会员 · Lv.3 · 电影是每秒二十四格的真理", state.subtitle)
        // 影豆 = points、积分 = userPoints、金币 = gold（字符串原样）
        assertEquals("0", state.values.stat("影豆"))
        assertEquals("10", state.values.stat("积分"))
        assertEquals("29.00", state.values.stat("金币"))
        // demoProfile 的 portrait 是空串，头像降级成首字母
        assertNull(state.avatarUrl)
        assertEquals(listOf("观看记录 128"), state.values.noticeLines)
        assertTrue(state.isLoggedIn)
    }

    @Test
    fun `profile failure keeps the layout and shows placeholder values`() = runTest {
        val viewModel = UserCenterViewModel(
            authRepository = FakeAuthRepository(restored = demoSession()),
            userRepository = FakeUserRepository(result = AppResult.Failure(AppError.Network(null))),
            variant = UserCenterVariant.B,
        )

        viewModel.events.test {
            assertEquals("网络异常，请检查网络后重试", awaitItem().text)
            cancelAndIgnoreRemainingEvents()
        }

        val state = viewModel.state.value
        assertTrue(state.isLoggedIn)
        assertNull(state.profile)
        assertEquals(UserCenterVariant.B, state.variant)
        assertTrue(state.sections.isNotEmpty())
        assertEquals("——", state.values.stat("影豆"))
        assertEquals("——", state.values.stat("金币"))
        assertTrue(state.values.noticeLines.isEmpty())
    }

    @Test
    fun `logout clears the session and asks the repository`() = runTest {
        val repository = FakeAuthRepository(restored = demoSession())
        val viewModel = UserCenterViewModel(authRepository = repository, variant = UserCenterVariant.B)

        viewModel.state.test {
            assertTrue(awaitItem().isLoggedIn)

            viewModel.logout()

            val state = awaitItem()
            assertNull(state.session)
            assertFalse(state.isLoggedIn)
            assertEquals("未登录", state.displayName)
            assertTrue(repository.logoutCalls == 1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `logout clears the loaded profile`() = runTest {
        val viewModel = UserCenterViewModel(
            authRepository = FakeAuthRepository(restored = demoSession()),
            userRepository = FakeUserRepository(),
        )
        assertEquals("示例昵称", viewModel.state.value.displayName)

        viewModel.logout()

        val state = viewModel.state.value
        assertNull(state.profile)
        assertNull(state.session)
        assertEquals("未登录", state.displayName)
        assertEquals("——", state.values.stat("积分"))
    }

    @Test
    fun `refresh fetches the profile again`() = runTest {
        val users = FakeUserRepository()
        val viewModel = UserCenterViewModel(
            authRepository = FakeAuthRepository(restored = demoSession()),
            userRepository = users,
        )
        assertEquals(1, users.loadCalls)

        viewModel.refresh()

        assertEquals(2, users.loadCalls)
        assertEquals("示例昵称", viewModel.state.value.displayName)
    }
}
