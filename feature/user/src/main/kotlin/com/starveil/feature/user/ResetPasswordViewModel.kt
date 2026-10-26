package com.starveil.feature.user

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.core.common.AppResult
import com.starveil.core.data.AuthRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 找回密码页状态持有者。
 *
 * 找回密码页只有"两次密码是否相同"一条前端校验，手机号与验证码都是
 * 空值直发后端；这里补齐四项校验，并复用注册页的 60 秒倒计时防刷。
 * 重置成功后不自动登录——成功后也是回登录页让用户用新密码登录。
 */
class ResetPasswordViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _state = MutableStateFlow(ResetPasswordUiState())
    val state: StateFlow<ResetPasswordUiState> = _state.asStateFlow()

    private val _events = Channel<ResetPasswordEvent>(Channel.BUFFERED)
    val events: Flow<ResetPasswordEvent> = _events.receiveAsFlow()

    /** 倒计时协程句柄：重复点重发时先取消旧的，避免两个循环同时改状态。 */
    private var countdownJob: Job? = null

    fun onPhoneChange(value: String) {
        _state.update { it.copy(phone = value, phoneError = null, formError = null) }
    }

    fun onCodeChange(value: String) {
        _state.update { it.copy(code = value, codeError = null, formError = null) }
    }

    fun onPasswordChange(value: String) {
        _state.update { it.copy(password = value, passwordError = null, formError = null) }
    }

    fun onPasswordConfirmChange(value: String) {
        _state.update {
            it.copy(passwordConfirm = value, passwordConfirmError = null, formError = null)
        }
    }

    fun togglePasswordVisibility() {
        _state.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    fun togglePasswordConfirmVisibility() {
        _state.update { it.copy(isPasswordConfirmVisible = !it.isPasswordConfirmVisible) }
    }

    /** 发送找回密码短信验证码（与注册验证码不是同一接口）。手机号不合法不发请求。 */
    fun sendCode() {
        val current = _state.value
        if (!current.canSendCode) return
        val phoneError = validateResetPhone(current.phone)
        if (phoneError != null) {
            _state.update { it.copy(phoneError = phoneError) }
        } else {
            _state.update { it.copy(isSendingCode = true, formError = null) }
            viewModelScope.launch { performSendCode(current.phone.trim()) }
        }
    }

    /** 校验失败不发请求；校验通过才调仓库。 */
    fun submit() {
        val current = _state.value
        if (current.isSubmitting) return
        val phoneError = validateResetPhone(current.phone)
        val codeError = validateCode(current.code)
        val passwordError = validateResetPassword(current.password)
        val confirmError = validateResetConfirm(current.password, current.passwordConfirm)
        _state.update {
            it.copy(
                phoneError = phoneError,
                codeError = codeError,
                passwordError = passwordError,
                passwordConfirmError = confirmError,
            )
        }
        val errors = listOf(phoneError, codeError, passwordError, confirmError)
        if (errors.all { it == null }) {
            _state.update { it.copy(isSubmitting = true, formError = null) }
            viewModelScope.launch { performReset(current) }
        }
    }

    private suspend fun performSendCode(phone: String) {
        when (val result = authRepository.sendResetCode(phone = phone)) {
            is AppResult.Success -> {
                _state.update { it.copy(isSendingCode = false) }
                startCountdown()
            }

            is AppResult.Failure -> _state.update {
                it.copy(isSendingCode = false, formError = result.error.toActionMessage())
            }
        }
    }

    private suspend fun performReset(current: ResetPasswordUiState) {
        val result = authRepository.resetPassword(
            phone = current.phone.trim(),
            password = current.password,
            passwordConfirm = current.passwordConfirm,
            code = current.code.trim(),
        )
        when (result) {
            is AppResult.Success -> {
                _state.update { it.copy(isSubmitting = false) }
                _events.send(ResetPasswordEvent.PasswordReset)
            }

            is AppResult.Failure -> _state.update {
                it.copy(isSubmitting = false, formError = result.error.toActionMessage())
            }
        }
    }

    /** 60 秒倒计时，与注册页同一套节奏（`RegisterViewModel.kt` 的 startCountdown）。 */
    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch { tickCountdown() }
    }

    /** 逐秒递减到 0，归零后按钮恢复可点。 */
    private suspend fun tickCountdown() {
        var remaining = RESET_CODE_COUNTDOWN_SECONDS
        while (remaining > 0) {
            setResendSeconds(remaining)
            delay(RESET_COUNTDOWN_TICK_MILLIS)
            remaining -= 1
        }
        setResendSeconds(0)
    }

    /** 只改倒计时字段，避免每个 tick 都把整份状态重构一遍。 */
    private fun setResendSeconds(value: Int) {
        _state.update { it.copy(resendSeconds = value) }
    }

    companion object {

        fun factory(authRepository: AuthRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { ResetPasswordViewModel(authRepository = authRepository) }
        }
    }
}

/** 手机号校验：空、非 11 位大陆号码。 */
private fun validateResetPhone(value: String): String? {
    val trimmed = value.trim()
    return when {
        trimmed.isEmpty() -> "请输入手机号"
        !RESET_PHONE_PATTERN.matches(trimmed) -> "请输入正确的手机号"
        else -> null
    }
}

/** 验证码校验：空。长度/位数由后端判断，这里只拦空值。 */
private fun validateCode(value: String): String? {
    val trimmed = value.trim()
    return if (trimmed.isEmpty()) "请输入验证码" else null
}

/** 新密码校验：空、过短、过长。阈值与登录页一致。 */
private fun validateResetPassword(value: String): String? = when {
    value.isEmpty() -> "请输入新密码"
    value.length < RESET_MIN_PASSWORD_LENGTH -> "密码至少 $RESET_MIN_PASSWORD_LENGTH 位"
    value.length > RESET_MAX_PASSWORD_LENGTH -> "密码最多 $RESET_MAX_PASSWORD_LENGTH 位"
    else -> null
}

/** 确认密码校验：空、与密码不一致。 */
private fun validateResetConfirm(password: String, confirm: String): String? = when {
    confirm.isEmpty() -> "请再次输入密码"
    confirm != password -> "两次输入的密码不一致"
    else -> null
}

private const val RESET_MIN_PASSWORD_LENGTH = 6
private const val RESET_MAX_PASSWORD_LENGTH = 32
private const val RESET_CODE_COUNTDOWN_SECONDS = 60
private const val RESET_COUNTDOWN_TICK_MILLIS = 1_000L

/** 大陆手机号：1 开头，第二位 3-9，共 11 位。 */
private val RESET_PHONE_PATTERN = Regex("^1[3-9]\\d{9}$")
