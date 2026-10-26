package com.starveil.feature.user

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.core.common.AppResult
import com.starveil.core.data.AuthRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 登录页状态持有者。
 *
 * 登录态不由返回值决定：后端下发 7 个 cookie，[AuthRepository.login] 把它们落成
 * [com.starveil.core.model.Session]。
 * 所以这里拿到 `AppResult.Success` 就等于已登录，不需要再解析响应体。
 *
 * 短信验证码登录没有对应接口：后端只有账号密码登录，
 * `VodService` 里的验证码接口只服务注册与找回密码。所以这里保持单一登录方式，
 * 补齐的是协议勾选、字段校验与错误文案（见交付说明的缺口清单）。
 */
class LoginViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private val _events = Channel<LoginEvent>(Channel.BUFFERED)
    val events: Flow<LoginEvent> = _events.receiveAsFlow()

    fun onUserNameChange(value: String) {
        _state.update { it.copy(userName = value, userNameError = null, formError = null) }
    }

    fun onPasswordChange(value: String) {
        _state.update { it.copy(password = value, passwordError = null, formError = null) }
    }

    /** 协议勾选：勾上就清掉提示，取消勾选不主动报错（等提交时再提示）。 */
    fun onAgreementChange(checked: Boolean) {
        _state.update { it.copy(agreedToTerms = checked, termsError = null) }
    }

    fun togglePasswordVisibility() {
        _state.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    /**
     * 校验失败不发请求；校验通过才调仓库，并把 [com.starveil.core.common.AppError] 翻成人话。
     *
     * 协议先于字段校验，与旧登录页一致。
     */
    fun submit() {
        val current = _state.value
        if (current.isSubmitting) return
        if (!current.agreedToTerms) {
            _state.update { it.copy(termsError = TERMS_REQUIRED_MESSAGE) }
            return
        }
        validateAndSubmit(current)
    }

    private fun validateAndSubmit(current: LoginUiState) {
        val userNameError = validateUserName(current.userName)
        val passwordError = validatePassword(current.password)
        _state.update {
            it.copy(
                userNameError = userNameError,
                passwordError = passwordError,
                termsError = null,
            )
        }
        if (userNameError != null || passwordError != null) return
        _state.update { it.copy(isSubmitting = true, formError = null) }
        viewModelScope.launch { performLogin(current.userName.trim(), current.password) }
    }

    private suspend fun performLogin(userName: String, password: String) {
        when (val result = authRepository.login(userName = userName, password = password)) {
            is AppResult.Success -> {
                _state.update { it.copy(isSubmitting = false, session = result.value) }
                _events.send(LoginEvent.SignedIn)
            }

            is AppResult.Failure -> _state.update {
                it.copy(isSubmitting = false, formError = result.error.toUserMessage())
            }
        }
    }

    companion object {

        fun factory(authRepository: AuthRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { LoginViewModel(authRepository = authRepository) }
        }
    }
}

/** 账号校验：空、过短、过长。没有任何前端校验，错了只能看后端 403。 */
private fun validateUserName(value: String): String? {
    val trimmed = value.trim()
    return when {
        trimmed.isEmpty() -> "请输入账号"
        trimmed.length < MIN_USER_NAME_LENGTH -> "账号至少 $MIN_USER_NAME_LENGTH 个字符"
        trimmed.length > MAX_USER_NAME_LENGTH -> "账号最多 $MAX_USER_NAME_LENGTH 个字符"
        else -> null
    }
}

/** 密码校验：空、过短、过长。 */
private fun validatePassword(value: String): String? = when {
    value.isEmpty() -> "请输入密码"
    value.length < MIN_PASSWORD_LENGTH -> "密码至少 $MIN_PASSWORD_LENGTH 位"
    value.length > MAX_PASSWORD_LENGTH -> "密码最多 $MAX_PASSWORD_LENGTH 位"
    else -> null
}

private const val MIN_USER_NAME_LENGTH = 2
private const val MAX_USER_NAME_LENGTH = 30
private const val MIN_PASSWORD_LENGTH = 6
private const val MAX_PASSWORD_LENGTH = 32
