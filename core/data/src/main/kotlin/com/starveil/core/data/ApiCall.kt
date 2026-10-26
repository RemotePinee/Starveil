package com.starveil.core.data

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.network.dto.ErrorEnvelopeDto
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException

/**
 * 传输层异常的统一收敛，仓库层共用。
 *
 * 分类与 [AppError] 一一对应，UI 才能区分"重试一下"和"去升级会员"。
 * HTTP 非 2xx 不能直接归为 [AppError.Http]：后端业务失败时 HTTP 是 400，
 * body 仍是 `{code,msg,data}` 壳，先尝试解析业务码。
 */
internal suspend fun <T> apiCall(block: suspend () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (http: HttpException) {
    AppResult.Failure(http.toAppError())
} catch (serialization: SerializationException) {
    AppResult.Failure(AppError.Serialization(serialization))
} catch (io: IOException) {
    AppResult.Failure(AppError.Network(io))
} catch (other: Exception) {
    AppResult.Failure(AppError.Unknown(other))
}

/** `data` 为空或 code != 200 时的统一业务失败。 */
internal fun businessFailure(code: Int, msg: String?): AppResult<Nothing> {
    val message = msg?.takeIf { it.isNotBlank() } ?: DEFAULT_BUSINESS_MESSAGE
    return AppResult.Failure(AppError.Business(code, message))
}

internal inline fun <T, R> AppResult<T>.flatMap(transform: (T) -> AppResult<R>): AppResult<R> =
    when (this) {
        is AppResult.Success -> transform(value)
        is AppResult.Failure -> this
    }

internal const val SUCCESS_CODE = 200

internal const val DEFAULT_BUSINESS_MESSAGE = "服务返回异常"

private val errorJson = Json { ignoreUnknownKeys = true }

private fun HttpException.toAppError(): AppError {
    val body = runCatching { response()?.errorBody()?.string() }.getOrNull()
    val envelope = body?.let { raw ->
        runCatching { errorJson.decodeFromString<ErrorEnvelopeDto>(raw) }.getOrNull()
    }
    return if (envelope != null && envelope.code != SUCCESS_CODE) {
        AppError.Business(envelope.code, envelope.msg ?: DEFAULT_BUSINESS_MESSAGE)
    } else {
        AppError.Http(code(), message().orEmpty())
    }
}
