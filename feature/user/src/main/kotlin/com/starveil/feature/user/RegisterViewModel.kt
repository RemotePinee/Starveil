package com.starveil.feature.user

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.core.common.AppResult
import com.starveil.core.data.AuthRepository
import com.starveil.core.data.RegisterPayload
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
 * 注册页状态持有者。
 *
 * 校验规则全部前移到本地：旧 `LoginActivity` 的注册分支只判空，手机号格式、两次密码是否一致
 * 都要等后端 403 才知道；这里一次校验完再决定发不发请求。
 * 注册成功只发一次性事件，不落会话——[AuthRepository.register] 的注释
 * 写明注册响应里的 cookie 不足以当会话用。
 *
 * 注册方式由构造参数 [mode] 注入，默认手机验证码注册（旧默认 `isOpenRegister = true`）。
 */
class RegisterViewModel(
    private val authRepository: AuthRepository,
    mode: RegisterMode = RegisterMode.PHONE_SMS,
) : ViewModel() {

    private val _state = MutableStateFlow(RegisterUiState(mode = mode))
    val state: StateFlow<RegisterUiState> = _state.asStateFlow()

    private val _events = Channel<RegisterEvent>(Channel.BUFFERED)
    val events: Flow<RegisterEvent> = _events.receiveAsFlow()

    /** 倒计时协程句柄：重复点重发时先取消旧的，避免两个循环同时改状态。 */
    private var countdownJob: Job? = null

    fun onUserNameChange(value: String) {
        _state.update { it.copy(userName = value, userNameError = null, formError = null) }
    }

    fun onPasswordChange(value: String) {
        _state.update { it.copy(password = value, passwordError = null, formError = null) }
    }

    fun onPasswordConfirmChange(value: String) {
        _state.update {
            it.copy(passwordConfirm = value, passwordConfirmError = null, formError = null)
        }
    }

    fun onInviteCodeChange(value: String) {
        _state.update { it.copy(inviteCode = value, formError = null) }
    }

    fun onSmsCodeChange(value: String) {
        _state.update { it.copy(smsCode = value, smsCodeError = null, formError = null) }
    }

    fun onAgreementChange(checked: Boolean) {
        _state.update { it.copy(agreedToTerms = checked, termsError = null) }
    }

    fun togglePasswordVisibility() {
        _state.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    fun togglePasswordConfirmVisibility() {
        _state.update { it.copy(isPasswordConfirmVisible = !it.isPasswordConfirmVisible) }
    }

    /** 发送注册短信验证码。手机号不合法不发请求，倒计时未走完直接忽略。 */
    fun sendCode() {
        val current = _state.value
        if (!current.canSendCode) return
        val phoneError = validatePhone(current.userName)
        if (phoneError != null) {
            _state.update { it.copy(userNameError = phoneError) }
        } else {
            _state.update { it.copy(isSendingCode = true, formError = null) }
            viewModelScope.launch { performSendCode(current.userName.trim()) }
        }
    }

    /** 协议 → 字段校验，全部通过才发请求；失败提示不触碰倒计时。 */
    fun submit() {
        val current = _state.value
        if (current.isSubmitting) return
        if (!current.agreedToTerms) {
            _state.update { it.copy(termsError = TERMS_REQUIRED_MESSAGE) }
            return
        }
        validateAndSubmit(current)
    }

    private fun validateAndSubmit(current: RegisterUiState) {
        val userNameError = validateAccount(current)
        val passwordError = validatePassword(current.password)
        val confirmError = validatePasswordConfirm(current.password, current.passwordConfirm)
        // 验证码只在手机号注册时校验：账号注册那一栏不渲染，也不该拦提交
        val smsCodeError = validateSmsCode(current)
        _state.update {
            it.copy(
                userNameError = userNameError,
                passwordError = passwordError,
                passwordConfirmError = confirmError,
                smsCodeError = smsCodeError,
                termsError = null,
            )
        }
        val errors = listOf(userNameError, passwordError, confirmError, smsCodeError)
        if (errors.any { it != null }) return
        _state.update { it.copy(isSubmitting = true, formError = null) }
        viewModelScope.launch { performRegister(current) }
    }

    private suspend fun performSendCode(phone: String) {
        when (val result = authRepository.sendRegisterCode(phone = phone)) {
            is AppResult.Success -> {
                _state.update { it.copy(isSendingCode = false) }
                startCountdown()
            }

            is AppResult.Failure -> _state.update {
                it.copy(isSendingCode = false, formError = result.error.toActionMessage())
            }
        }
    }

    private suspend fun performRegister(current: RegisterUiState) {
        val result = authRepository.registerWithCode(
            RegisterPayload(
                userName = current.userName.trim(),
                password = current.password,
                passwordConfirm = current.passwordConfirm,
                inviteCode = current.inviteCode.trim(),
                smsCode = if (current.mode == RegisterMode.PHONE_SMS) current.smsCode.trim() else "",
            ),
        )
        when (result) {
            is AppResult.Success -> {
                _state.update { it.copy(isSubmitting = false) }
                _events.send(RegisterEvent.Registered)
            }

            is AppResult.Failure -> _state.update {
                it.copy(isSubmitting = false, formError = result.error.toActionMessage())
            }
        }
    }

    /** 60 秒倒计时，每秒刷新一次剩余秒数；归零后按钮恢复可点。 */
    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch { tickCountdown() }
    }

    /** 逐秒递减到 0，归零后按钮恢复可点。 */
    private suspend fun tickCountdown() {
        var remaining = CODE_COUNTDOWN_SECONDS
        while (remaining > 0) {
            setResendSeconds(remaining)
            delay(COUNTDOWN_TICK_MILLIS)
            remaining -= 1
        }
        setResendSeconds(0)
    }

    /** 只改倒计时字段，避免每个 tick 都把整份状态重构一遍。 */
    private fun setResendSeconds(value: Int) {
        _state.update { it.copy(resendSeconds = value) }
    }

    companion object {

        fun factory(
            authRepository: AuthRepository,
            mode: RegisterMode = RegisterMode.PHONE_SMS,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { RegisterViewModel(authRepository = authRepository, mode = mode) }
        }
    }
}

/** 账号一栏按注册方式校验：手机注册查格式，普通注册查长度（完全不校验）。 */
private fun validateAccount(state: RegisterUiState): String? = when (state.mode) {
    RegisterMode.PHONE_SMS -> validatePhone(state.userName)
    RegisterMode.ACCOUNT -> validateUserName(state.userName)
}

/** 手机号校验：空、非 11 位大陆号码。完全不校验格式。 */
private fun validatePhone(value: String): String? {
    val trimmed = value.trim()
    return when {
        trimmed.isEmpty() -> "请输入账号"
        !PHONE_PATTERN.matches(trimmed) -> "请输入正确的手机号"
        else -> null
    }
}


/** 验证码只在手机号注册时出现；账号注册返回 null，不参与拦截。 */
private fun validateSmsCode(state: RegisterUiState): String? {
    if (state.mode != RegisterMode.PHONE_SMS) return null
    val code = state.smsCode.trim()
    return when {
        code.isEmpty() -> "请输入验证码"
        !code.matches(Regex("^\\d{4,6}$")) -> "验证码为 4-6 位数字"
        else -> null
    }
}
/** 普通账号校验：空、过短、过长。阈值与登录页一致。 */
private fun validateUserName(value: String): String? {
    val trimmed = value.trim()
    return when {
        trimmed.isEmpty() -> "请输入账号"
        trimmed.length < MIN_USER_NAME_LENGTH -> "账号至少 $MIN_USER_NAME_LENGTH 个字符"
        trimmed.length > MAX_USER_NAME_LENGTH -> "账号最多 $MAX_USER_NAME_LENGTH 个字符"
        else -> null
    }
}

/** 密码校验：空、过短、过长。阈值与登录页保持一致。 */
private fun validatePassword(value: String): String? = when {
    value.isEmpty() -> "请输入密码"
    value.length < MIN_PASSWORD_LENGTH -> "密码至少 $MIN_PASSWORD_LENGTH 位"
    value.length > MAX_PASSWORD_LENGTH -> "密码最多 $MAX_PASSWORD_LENGTH 位"
    else -> null
}

/** 确认密码校验：空、与密码不一致。 */
private fun validatePasswordConfirm(password: String, confirm: String): String? = when {
    confirm.isEmpty() -> "请再次输入密码"
    confirm != password -> "两次输入的密码不一致"
    else -> null
}

private const val MIN_USER_NAME_LENGTH = 2
private const val MAX_USER_NAME_LENGTH = 30
private const val MIN_PASSWORD_LENGTH = 6
private const val MAX_PASSWORD_LENGTH = 32
private const val CODE_COUNTDOWN_SECONDS = 60
private const val COUNTDOWN_TICK_MILLIS = 1_000L

/** 大陆手机号：1 开头，第二位 3-9，共 11 位。 */
private val PHONE_PATTERN = Regex("^1[3-9]\\d{9}$")
