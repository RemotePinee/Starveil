package com.starveil.core.data

import com.starveil.core.common.AppResult
import com.starveil.core.network.GbookApi

/**
 * 反馈提交（`POST v1.gbook`）。
 *
 * 服务端限制：登录必需、≤300 字、同一用户 60 秒一条。
 * 本地只做空串拦截，长度交给服务端截断（后端 `mb_substr` 到 300），避免两边规则各说各话。
 */
interface FeedbackRepository {

    suspend fun submit(content: String): AppResult<Unit> =
        AppResult.Failure(com.starveil.core.common.AppError.Unknown(UnsupportedOperationException()))
}

class DefaultFeedbackRepository(private val api: GbookApi) : FeedbackRepository {

    override suspend fun submit(content: String): AppResult<Unit> =
        apiCall { api.submit(content) }.requireSuccess()
}
