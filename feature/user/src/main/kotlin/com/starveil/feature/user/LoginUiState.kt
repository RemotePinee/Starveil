package com.starveil.feature.user

import com.starveil.core.model.Session

/**
 * 登录页状态。不可变，通过 StateFlow 暴露（AGENTS.md 第 5 节）。
 *
 * 错误分三层，对应旧登录页完全没有校验的问题：
 * - [userNameError] / [passwordError]：字段级校验，贴在输入框下面；
 * - [termsError]：未勾选服务协议，提示口径沿用旧登录页；
 * - [formError]：后端业务拒绝或网络异常，展示在按钮上方；
 * - [session]：登录成功后的会话，其余场景一律 null。
 *
 * 契约来源：旧 
 * （`POST /v1.auth/login`，表单 `user_name` / `user_pwd`）。
 */
data class LoginUiState(
    val userName: String = "",
    val password: String = "",
    val userNameError: String? = null,
    val passwordError: String? = null,
    val formError: String? = null,
    val termsError: String? = null,
    val isSubmitting: Boolean = false,
    val agreedToTerms: Boolean = false,
    val isPasswordVisible: Boolean = false,
    val session: Session? = null,
) {

    /** 提交中禁用按钮，避免里重复点登录连发多次请求的问题。 */
    val canSubmit: Boolean
        get() = !isSubmitting

    val isLoggedIn: Boolean
        get() = session?.isLoggedIn == true
}

/** 登录成功是一次性事件，不能放进 [LoginUiState]（旋屏会重复触发导航）。 */
sealed interface LoginEvent {

    /** 登录成功，调用方应关闭登录页并回用户中心。 */
    data object SignedIn : LoginEvent
}
