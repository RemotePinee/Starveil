package com.starveil.feature.user

import app.cash.turbine.test
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 找回密码页。四条：验证码为空、成功、业务失败、网络失败。
 *
 * 找回密码页把空验证码直发后端，只在返回 403 后弹一句失败；
 * 第一条的重点就是"空验证码不发请求"。
 */
class ResetPasswordViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `blank code is rejected without calling the repository`() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = ResetPasswordViewModel(authRepository = repository)

        viewModel.onPhoneChange("13800138000")
        viewModel.onPasswordChange("secret123")
        viewModel.onPasswordConfirmChange("secret123")
        viewModel.submit()

        val state = viewModel.state.value
        assertEquals("请输入验证码", state.codeError)
        assertTrue(repository.resetPasswordCalls == 0)
        assertFalse(state.isSubmitting)
    }

    @Test
    fun `valid form resets the password and emits the reset event`() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = ResetPasswordViewModel(authRepository = repository)

        viewModel.events.test {
            viewModel.onPhoneChange("13800138000")
            viewModel.onCodeChange("8642")
            viewModel.onPasswordChange("secret123")
            viewModel.onPasswordConfirmChange("secret123")
            viewModel.submit()

            assertEquals(ResetPasswordEvent.PasswordReset, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        val state = viewModel.state.value
        assertFalse(state.isSubmitting)
        assertTrue(repository.resetPasswordCalls == 1)
        assertEquals("13800138000", repository.lastPhone)
        assertEquals("8642", repository.lastResetCode)
    }

    @Test
    fun `business rejection shows the backend message`() = runTest {
        val repository = FakeAuthRepository(
            resetResult = AppResult.Failure(
                AppError.Business(code = 500, message = "验证码已过期"),
            ),
        )
        val viewModel = ResetPasswordViewModel(authRepository = repository)

        viewModel.onPhoneChange("13800138000")
        viewModel.onCodeChange("8642")
        viewModel.onPasswordChange("secret123")
        viewModel.onPasswordConfirmChange("secret123")
        viewModel.submit()

        val state = viewModel.state.value
        assertEquals("验证码已过期", state.formError)
        assertFalse(state.isSubmitting)
    }

    @Test
    fun `network failure shows a readable network message`() = runTest {
        val repository = FakeAuthRepository(
            resetResult = AppResult.Failure(AppError.Network(cause = null)),
        )
        val viewModel = ResetPasswordViewModel(authRepository = repository)

        viewModel.onPhoneChange("13800138000")
        viewModel.onCodeChange("8642")
        viewModel.onPasswordChange("secret123")
        viewModel.onPasswordConfirmChange("secret123")
        viewModel.submit()

        val state = viewModel.state.value
        assertNull(state.codeError)
        assertTrue(state.formError.orEmpty().contains("网络异常"))
        assertFalse(state.isSubmitting)
    }
}
