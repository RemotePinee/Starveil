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
 * 登录页。协议拦截、空账号、空密码、成功、业务失败、网络失败、HTTP 文案映射。
 *
 * "不发请求"是本组用例的重点——没有任何前端校验，空表单会直接打后端；
 * 协议拦截则对应旧登录页 的勾选判断。
 */
class LoginViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `unchecked agreement blocks login with the legacy wording`() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = LoginViewModel(authRepository = repository)

        viewModel.onUserNameChange("demo")
        viewModel.onPasswordChange("secret123")
        viewModel.submit()

        val state = viewModel.state.value
        assertEquals(TERMS_REQUIRED_MESSAGE, state.termsError)
        assertTrue(state.termsError.orEmpty().contains("若不认同本服务协议将无法使用本软件"))
        assertNull(state.userNameError)
        assertTrue(repository.loginCalls == 0)
        assertFalse(state.isSubmitting)
    }

    @Test
    fun `checking the agreement clears the terms error`() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = LoginViewModel(authRepository = repository)

        viewModel.submit()
        viewModel.onAgreementChange(true)

        assertNull(viewModel.state.value.termsError)
        assertTrue(viewModel.state.value.agreedToTerms)
    }

    @Test
    fun `blank account is rejected without calling the repository`() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = LoginViewModel(authRepository = repository)

        viewModel.onAgreementChange(true)
        viewModel.submit()

        val state = viewModel.state.value
        assertEquals("请输入账号", state.userNameError)
        assertTrue(repository.loginCalls == 0)
        assertFalse(state.isSubmitting)
    }

    @Test
    fun `blank password is rejected without calling the repository`() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = LoginViewModel(authRepository = repository)

        viewModel.onAgreementChange(true)
        viewModel.onUserNameChange("demo")
        viewModel.submit()

        val state = viewModel.state.value
        assertEquals("请输入密码", state.passwordError)
        assertTrue(repository.loginCalls == 0)
    }

    @Test
    fun `valid credentials emit signed in and store the session`() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = LoginViewModel(authRepository = repository)

        viewModel.events.test {
            viewModel.onAgreementChange(true)
            viewModel.onUserNameChange("demo")
            viewModel.onPasswordChange("secret123")
            viewModel.submit()

            assertEquals(LoginEvent.SignedIn, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        val state = viewModel.state.value
        assertFalse(state.isSubmitting)
        assertTrue(state.isLoggedIn)
        assertNotNull(state.session)
        assertEquals("42", state.session?.userId)
        assertEquals("demo", repository.lastUserName)
        assertTrue(repository.loginCalls == 1)
    }

    @Test
    fun `business rejection shows the backend message`() = runTest {
        val repository = FakeAuthRepository(
            loginResult = AppResult.Failure(AppError.Business(code = 403, message = "账号或密码错误")),
        )
        val viewModel = LoginViewModel(authRepository = repository)

        viewModel.onAgreementChange(true)
        viewModel.onUserNameChange("demo")
        viewModel.onPasswordChange("secret123")
        viewModel.submit()

        val state = viewModel.state.value
        assertEquals("账号或密码错误", state.formError)
        assertNull(state.session)
        assertFalse(state.isSubmitting)
    }

    @Test
    fun `network failure shows a readable network message`() = runTest {
        val repository = FakeAuthRepository(
            loginResult = AppResult.Failure(AppError.Network(cause = null)),
        )
        val viewModel = LoginViewModel(authRepository = repository)

        viewModel.onAgreementChange(true)
        viewModel.onUserNameChange("demo")
        viewModel.onPasswordChange("secret123")
        viewModel.submit()

        val state = viewModel.state.value
        assertNotNull(state.formError)
        assertTrue(state.formError.orEmpty().contains("网络异常"))
        assertNull(state.session)
    }

    @Test
    fun `http 400 401 and 403 are mapped to distinct messages`() = runTest {
        assertEquals(
            "提交的信息有误，请检查后重试",
            loginErrorOf(AppError.Http(code = 400, message = "")),
        )
        assertEquals(
            "登录状态已失效，请重新登录",
            loginErrorOf(AppError.Http(code = 401, message = "")),
        )
        assertEquals(
            "没有权限，或账号已被限制",
            loginErrorOf(AppError.Http(code = 403, message = "")),
        )
    }

    @Test
    fun `toggling password visibility flips the state flag`() = runTest {
        val viewModel = LoginViewModel(authRepository = FakeAuthRepository())

        assertFalse(viewModel.state.value.isPasswordVisible)
        viewModel.togglePasswordVisibility()
        assertTrue(viewModel.state.value.isPasswordVisible)
        viewModel.togglePasswordVisibility()
        assertFalse(viewModel.state.value.isPasswordVisible)
    }

    /** 跑一次登录，返回后端/网络失败映射出的文案。 */
    private suspend fun loginErrorOf(error: AppError): String? {
        val viewModel = LoginViewModel(
            authRepository = FakeAuthRepository(loginResult = AppResult.Failure(error)),
        )
        viewModel.onAgreementChange(true)
        viewModel.onUserNameChange("demo")
        viewModel.onPasswordChange("secret123")
        viewModel.submit()
        return viewModel.state.value.formError
    }
}
