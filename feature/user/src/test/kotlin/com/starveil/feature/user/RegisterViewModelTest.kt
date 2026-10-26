package com.starveil.feature.user

import app.cash.turbine.test
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 注册页。协议拦截、两种注册方式、验证码倒计时、成功与失败。
 *
 * 重点与旧 `LoginActivity` 的注册分支对照：只判空、倒计时失败不回滚、
 * 重复点会连发请求；这里逐条补上。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RegisterViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `unchecked agreement blocks registration`() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = RegisterViewModel(authRepository = repository)

        viewModel.onUserNameChange("13800138000")
        viewModel.onPasswordChange("secret123")
        viewModel.onPasswordConfirmChange("secret123")
        viewModel.submit()

        val state = viewModel.state.value
        assertEquals(TERMS_REQUIRED_MESSAGE, state.termsError)
        assertTrue(repository.registerCalls == 0)
        assertFalse(state.isSubmitting)
    }

    @Test
    fun `blank account is rejected without calling the repository`() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = RegisterViewModel(authRepository = repository)

        viewModel.onAgreementChange(true)
        viewModel.onPasswordChange("secret123")
        viewModel.onPasswordConfirmChange("secret123")
        viewModel.submit()

        val state = viewModel.state.value
        assertEquals("请输入账号", state.userNameError)
        assertTrue(repository.registerCalls == 0)
        assertFalse(state.isSubmitting)
    }

    @Test
    fun `phone mode rejects a malformed mobile number`() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = RegisterViewModel(authRepository = repository)

        viewModel.onAgreementChange(true)
        viewModel.onUserNameChange("12345")
        viewModel.onPasswordChange("secret123")
        viewModel.onPasswordConfirmChange("secret123")
        viewModel.submit()

        assertEquals("请输入正确的手机号", viewModel.state.value.userNameError)
        assertTrue(repository.registerCalls == 0)
    }

    @Test
    fun `mismatched confirmation is rejected without calling the repository`() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = RegisterViewModel(authRepository = repository)

        viewModel.onAgreementChange(true)
        viewModel.onUserNameChange("13800138000")
        viewModel.onPasswordChange("secret123")
        viewModel.onPasswordConfirmChange("secret124")
        viewModel.submit()

        val state = viewModel.state.value
        assertEquals("两次输入的密码不一致", state.passwordConfirmError)
        assertTrue(repository.registerCalls == 0)
        assertFalse(state.isSubmitting)
    }

    @Test
    fun `another code request during the countdown is ignored`() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = RegisterViewModel(authRepository = repository)

        viewModel.onUserNameChange("13800138000")
        viewModel.sendCode()
        viewModel.sendCode()

        assertTrue(repository.sendRegisterCodeCalls == 1)
        assertTrue(viewModel.state.value.resendSeconds == 60)
        assertFalse(viewModel.state.value.canSendCode)
    }

    @Test
    fun `countdown reaches zero and re-enables the code button`() = runTest {
        val viewModel = RegisterViewModel(authRepository = FakeAuthRepository())

        viewModel.onUserNameChange("13800138000")
        viewModel.sendCode()
        assertEquals(60, viewModel.state.value.resendSeconds)
        assertEquals("60s 后重发", viewModel.state.value.resendLabel)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(59, viewModel.state.value.resendSeconds)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, viewModel.state.value.resendSeconds)
        assertTrue(viewModel.state.value.canSendCode)
        assertEquals("获取验证码", viewModel.state.value.resendLabel)
    }

    @Test
    fun `failed code request keeps the button retryable and shows the error`() = runTest {
        val repository = FakeAuthRepository(
            sendCodeResult = AppResult.Failure(AppError.Business(code = 403, message = "发送过于频繁")),
        )
        val viewModel = RegisterViewModel(authRepository = repository)

        viewModel.onUserNameChange("13800138000")
        viewModel.sendCode()

        val state = viewModel.state.value
        assertEquals("发送过于频繁", state.formError)
        assertEquals(0, state.resendSeconds)
        assertTrue(state.canSendCode)
        assertFalse(state.isSendingCode)
    }

    @Test
    fun `registration failure does not overwrite the countdown`() = runTest {
        val repository = FakeAuthRepository(
            registerResult = AppResult.Failure(AppError.Business(code = 400, message = "账号已存在")),
        )
        val viewModel = RegisterViewModel(authRepository = repository)

        viewModel.onAgreementChange(true)
        viewModel.onUserNameChange("13800138000")
        viewModel.onPasswordChange("secret123")
        viewModel.onPasswordConfirmChange("secret123")
        viewModel.sendCode()
        // 手机注册现在必须填验证码才能提交：发码之后还要真的把码填上，
        // 否则会被本地校验拦下，注册请求根本发不出去
        viewModel.onSmsCodeChange("1234")
        viewModel.submit()

        val state = viewModel.state.value
        assertEquals("账号已存在", state.formError)
        assertEquals(60, state.resendSeconds)
        assertFalse(state.canSendCode)
    }

    @Test
    fun `valid form registers and emits the registered event`() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = RegisterViewModel(authRepository = repository)

        viewModel.events.test {
            viewModel.onAgreementChange(true)
            viewModel.onUserNameChange("13800138000")
            viewModel.onPasswordChange("secret123")
            viewModel.onPasswordConfirmChange("secret123")
            viewModel.onInviteCodeChange("INV-9")
            viewModel.onSmsCodeChange("1234")
            viewModel.submit()
            assertEquals(RegisterEvent.Registered, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        val state = viewModel.state.value
        assertFalse(state.isSubmitting)
        assertTrue(repository.registerCalls == 1)
        assertEquals("13800138000", repository.lastUserName)
        assertEquals("INV-9", repository.lastUid)
        assertEquals("secret123", repository.lastRegisterPasswordConfirm)
    }

    @Test
    fun `account mode accepts a plain account and never sends a code`() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = RegisterViewModel(authRepository = repository, mode = RegisterMode.ACCOUNT)

        viewModel.events.test {
            viewModel.onAgreementChange(true)
            viewModel.onUserNameChange("starveil_fan")
            viewModel.onPasswordChange("secret123")
            viewModel.onPasswordConfirmChange("secret123")
            viewModel.submit()

            assertEquals(RegisterEvent.Registered, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(repository.registerCalls == 1)
        assertEquals("starveil_fan", repository.lastUserName)

        viewModel.sendCode()
        assertTrue(repository.sendRegisterCodeCalls == 0)
        assertFalse(viewModel.state.value.showsCodeButton)
    }

    @Test
    fun `account mode rejects a too short account with the account wording`() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = RegisterViewModel(authRepository = repository, mode = RegisterMode.ACCOUNT)

        viewModel.onAgreementChange(true)
        viewModel.onUserNameChange("a")
        viewModel.onPasswordChange("secret123")
        viewModel.onPasswordConfirmChange("secret123")
        viewModel.submit()

        val state = viewModel.state.value
        assertEquals("账号至少 2 个字符", state.userNameError)
        assertTrue(repository.registerCalls == 0)
    }

    @Test
    fun `business rejection shows the backend message`() = runTest {
        val repository = FakeAuthRepository(
            registerResult = AppResult.Failure(
                AppError.Business(code = 500, message = "该手机号已注册"),
            ),
        )
        val viewModel = RegisterViewModel(authRepository = repository)

        viewModel.onAgreementChange(true)
        viewModel.onUserNameChange("13800138000")
        viewModel.onPasswordChange("secret123")
        viewModel.onPasswordConfirmChange("secret123")
        viewModel.onSmsCodeChange("1234")
        viewModel.submit()

        val state = viewModel.state.value
        assertEquals("该手机号已注册", state.formError)
        assertNull(state.userNameError)
        assertFalse(state.isSubmitting)
    }
}
