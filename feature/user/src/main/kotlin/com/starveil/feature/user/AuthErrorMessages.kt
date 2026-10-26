package com.starveil.feature.user

import com.starveil.core.common.AppError

/**
 * 登录 / 注册 / 找回密码共用的错误文案。
 *
 * 所有失败都只弹一句"操作失败"，会让用户分不清是密码错了还是服务器挂了。
 * 按 `AppError` 分类翻译：
 * - `Business` 有后端 `msg` 时优先用后端文案（后端最清楚是"账号已存在"还是"验证码过期"）；
 * - `Http` 按 400 / 401 / 403 分开映射，其余才落到"服务异常（code）"。
 *
 * 注意后端业务失败时 HTTP 也是 400，`ApiCall.kt:16-60` 已先尝试解析响应壳，
 * 所以走到 `Http` 分支说明响应体没有可用的 `{code,msg}`。
 */
internal fun AppError.toUserMessage(): String = toAuthMessage(fallback = "登录失败，请稍后重试")

internal fun AppError.toActionMessage(): String = toAuthMessage(fallback = "操作失败，请稍后重试")

private fun AppError.toAuthMessage(fallback: String): String = when (this) {
    is AppError.Business -> message.ifBlank { httpMessage(code) ?: fallback }
    is AppError.Http -> httpMessage(code) ?: "服务异常（$code）"
    is AppError.Network -> "网络异常，请检查网络后重试"
    is AppError.Serialization -> "数据解析失败，请稍后重试"
    is AppError.Unknown -> "未知错误，请稍后重试"
}

private fun httpMessage(code: Int): String? = when (code) {
    400 -> "提交的信息有误，请检查后重试"
    401 -> "登录状态已失效，请重新登录"
    403 -> "没有权限，或账号已被限制"
    else -> null
}

/**
 * 未勾选服务协议时的提示，口语与旧登录页一致
 * （：「若不认同本服务协议将无法使用本软件！请勾选用户使用协议」）。
 */
internal const val TERMS_REQUIRED_MESSAGE = "若不认同本服务协议将无法使用本软件！请勾选用户使用协议"
