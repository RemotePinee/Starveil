package com.starveil.feature.live

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

/**
 * 从 Media3 的异常链里挖出 HTTP 状态码。
 *
 * `PlaybackException.errorCode` 只有"HTTP 状态不对"这一个粗分类，具体是 403、404 还是 500
 * 只存在于 cause 里的 [HttpDataSource.InvalidResponseCodeException]。挖出来才能告诉用户
 * "这个台被源站拒了"还是"这个地址已经失效"。
 */
internal fun httpStatusOf(error: PlaybackException): Int? =
    generateSequence<Throwable>(error.cause) { it.cause }
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()
        ?.responseCode
