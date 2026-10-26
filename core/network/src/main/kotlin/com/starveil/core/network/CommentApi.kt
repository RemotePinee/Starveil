package com.starveil.core.network

import com.starveil.core.network.dto.ApiEnvelopeDto
import com.starveil.core.network.dto.CommentListDto
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * 评论接口（路径常量见 [ApiPaths.COMMENT]）。
 *
 * 单独一个接口而不塞进 [StarveilApi]：`StarveilApi` 被十几个测试 fake 实现，
 * 每加一个方法都要改一遍；评论只有详情页用，独立接口更省事（与 [VodTypeApi] 同口径）。
 *
 * 两条契约（服务端 `CommentController.php`）：
 * - 列表公开，query `vod_id` / `page` / `limit`，`limit` 被夹在 1..60；
 * - 发布是表单 POST，未登录回 `code = 401`（HTTP 状态也是 401，body 仍是壳）。
 */
interface CommentApi {

    @GET(ApiPaths.COMMENT)
    suspend fun list(
        @Query("vod_id") vodId: Int,
        @Query("page") page: Int,
        @Query("limit") limit: Int,
    ): ApiEnvelopeDto<CommentListDto>

    /**
     * 发布评论。
     *
     * 成功时 `data` 为 null（`Json::ok(null, '评论已发布')`），所以泛型用 [Unit]；
     * 响应壳仍按 `code == 200` 判定成功，调用方走 `requireSuccess()`。
     */
    @FormUrlEncoded
    @POST(ApiPaths.COMMENT)
    suspend fun post(
        @Field("vod_id") vodId: String,
        @Field("content") content: String,
    ): ApiEnvelopeDto<Unit>
}
