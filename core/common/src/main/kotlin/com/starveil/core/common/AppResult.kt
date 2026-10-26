package com.starveil.core.common

/**
 * 所有可能失败的调用统一返回本类型，禁止向 UI 抛裸异常。
 * 约定见 AGENTS.md 第 5 节。
 */
sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

/**
 * 错误分类必须能区分"网络断了"、"后端业务拒绝"和"解析不了"，
 * 否则 UI 只能统一弹一句"网络错误"。
 */
sealed interface AppError {
    data class Network(val cause: Throwable?) : AppError
    data class Http(val code: Int, val message: String) : AppError
    data class Business(val code: Int, val message: String) : AppError
    data class Serialization(val cause: Throwable?) : AppError
    data class Unknown(val cause: Throwable?) : AppError
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(value)
    return this
}

inline fun <T> AppResult<T>.onFailure(action: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) action(error)
    return this
}

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.value
