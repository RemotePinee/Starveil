package com.starveil.core.data

import com.starveil.core.common.AppResult
import com.starveil.core.network.dto.ApiEnvelopeDto

/**
 * 响应壳 -> 取 data 的收敛，仓库层共用（与 [apiCall] / [businessFailure] 一套）。
 *
 * 后端的三个约定决定了这里必须两步判断（见 `ApiEnvelopeDto` 注释）：
 * 1. `code != 200` 一律是业务失败，即使 HTTP 是 200；
 * 2. `code == 200` 但 `data` 为 null，同样按业务失败处理——不能把 null 塞进 UI；
 * 3. HTTP 400 的错误体也会走到这里，业务码要原样透传给 UI。
 */
internal fun <T> AppResult<ApiEnvelopeDto<T>>.requireData(): AppResult<T> = flatMap { envelope ->
    if (envelope.code != SUCCESS_CODE) return@flatMap businessFailure(envelope.code, envelope.msg)
    envelope.data?.let { AppResult.Success(it) } ?: businessFailure(envelope.code, envelope.msg)
}

/** 只关心 `code` 的接口（注册、找回密码、收藏增删）用这个，`data` 是提示串，忽略即可。 */
internal fun AppResult<ApiEnvelopeDto<*>>.requireSuccess(): AppResult<Unit> = flatMap { envelope ->
    if (envelope.code != SUCCESS_CODE) return@flatMap businessFailure(envelope.code, envelope.msg)
    AppResult.Success(Unit)
}
