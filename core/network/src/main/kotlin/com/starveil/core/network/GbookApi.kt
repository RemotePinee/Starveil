package com.starveil.core.network

import com.starveil.core.network.dto.ApiEnvelopeDto
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

/**
 * 反馈（留言板 `v1.gbook`）。
 *
 * 独立接口，理由同 [PointsApi]：`StarveilApi` 被十几个测试 fake 实现。
 * 服务端口径：登录必需、单条 ≤300 字、同一用户 60 秒冷却（`GbookController::postIndex`）。
 */
interface GbookApi {

    @FormUrlEncoded
    @POST(ApiPaths.FEEDBACK)
    suspend fun submit(@Field("content") content: String): ApiEnvelopeDto<String>
}
