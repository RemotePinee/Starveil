package com.starveil.feature.user

/**
 * 找回密码页状态。不可变，通过 StateFlow 暴露（AGENTS.md 第 5 节）。
 *
 * 契约：`POST v1.auth/findpass`，表单
 * `user_phone` / `user_code` / `user_pwd` / `user_pwd2`。
 * 与注册页不同，这里的验证码会随请求一起提交，后端据此判断短信是否有效。
 */
data class ResetPasswordUiState(
    val phone: String = "",
    val code: String = "",
    val password: String = "",
    val passwordConfirm: String = "",
    val phoneError: String? = null,
    val codeError: String? = null,
    val passwordError: String? = null,
    val passwordConfirmError: String? = null,
    val formError: String? = null,
    val isSubmitting: Boolean = false,
    val isSendingCode: Boolean = false,
    val resendSeconds: Int = 0,
    val isPasswordVisible: Boolean = false,
    val isPasswordConfirmVisible: Boolean = false,
) {

    /** 提交中禁用按钮，避免重复提交（可连点多次重置）。 */
    val canSubmit: Boolean
        get() = !isSubmitting

    /** 倒计时未走完或正在请求时，验证码按钮不可再点。 */
    val canSendCode: Boolean
        get() = !isSendingCode && resendSeconds == 0

    /** 验证码按钮文案：请求中与倒计时期间都不可点，文案随状态变化。 */
    val resendLabel: String
        get() = when {
            isSendingCode -> "发送中…"
            resendSeconds > 0 -> "${resendSeconds}s 后重发"
            else -> "获取验证码"
        }
}

/** 重置成功是一次性事件，不能放进 [ResetPasswordUiState]（旋屏会重复导航）。 */
sealed interface ResetPasswordEvent {

    /** 密码已重置，调用方应回登录页让用户用新密码登录。 */
    data object PasswordReset : ResetPasswordEvent
}
