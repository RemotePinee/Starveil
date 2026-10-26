package com.starveil.feature.user

/**
 * 注册页状态。不可变，通过 StateFlow 暴露（AGENTS.md 第 5 节）。
 *
 * 两种注册方式由 [mode] 区分（对应旧 `openRegister` 开关，见 [RegisterMode]）：
 * - [RegisterMode.PHONE_SMS]：手机号 + 短信验证码 + 密码 + 确认密码；
 * - [RegisterMode.ACCOUNT]：账号 + 密码 + 确认密码，不发短信。
 *
 * 注册接口只收 `user_name` / `user_pwd` / `user_pwd2` / `uid`
 * 短信验证码走独立接口
 * 所以短信只发不收——手机注册同样是先发码再注册。
 *
 * 错误分三层，三页一致：字段级错误贴在输入框下，[termsError] 在协议行下，
 * 后端/网络错误放 [formError] 展示在按钮上方。
 */
data class RegisterUiState(
    val mode: RegisterMode = RegisterMode.PHONE_SMS,
    val userName: String = "",
    val password: String = "",
    val passwordConfirm: String = "",
    val inviteCode: String = "",
    /** 手机号注册的短信验证码。账号注册不显示这一栏，保持空串。 */
    val smsCode: String = "",
    val userNameError: String? = null,
    val passwordError: String? = null,
    val passwordConfirmError: String? = null,
    val smsCodeError: String? = null,
    val formError: String? = null,
    val termsError: String? = null,
    val agreedToTerms: Boolean = false,
    val isSubmitting: Boolean = false,
    val isSendingCode: Boolean = false,
    val resendSeconds: Int = 0,
    val isPasswordVisible: Boolean = false,
    val isPasswordConfirmVisible: Boolean = false,
) {

    /** 提交中禁用按钮，避免重复点注册连发多次请求（无此保护）。 */
    val canSubmit: Boolean
        get() = !isSubmitting

    /** 普通账号注册没有短信，按钮始终不可点。 */
    val showsCodeButton: Boolean
        get() = mode == RegisterMode.PHONE_SMS

    /** 倒计时未走完、正在请求、或不是手机注册时，验证码按钮不可点。 */
    val canSendCode: Boolean
        get() = showsCodeButton && !isSendingCode && resendSeconds == 0

    /** 验证码按钮文案：倒计时期间显示剩余秒数。 */
    val resendLabel: String
        get() = when {
            isSendingCode -> "发送中…"
            resendSeconds > 0 -> "${resendSeconds}s 后重发"
            else -> "获取验证码"
        }

    /** 账号一栏的占位符随注册方式变化。 */
    val accountPlaceholder: String
        get() = mode.accountPlaceholder
}

/** 注册成功是一次性事件，不能放进 [RegisterUiState]（旋屏会重复触发导航）。 */
sealed interface RegisterEvent {

    /** 注册成功，调用方应回登录页（后端注册响应不带可用会话）。 */
    data object Registered : RegisterEvent
}
