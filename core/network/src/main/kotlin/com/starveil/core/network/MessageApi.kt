package com.starveil.core.network

import com.starveil.core.network.dto.ApiEnvelopeDto
import com.starveil.core.network.dto.MessageListDto
import com.starveil.core.network.dto.MessageReadDto
import com.starveil.core.network.dto.VersionDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * 消息中心与在线更新。
 *
 * 独立接口而不是塞进 [StarveilApi]：后者被十几个测试 fake 实现，
 * 每加一个方法都要改一圈（与 [HomeContentApi] 同一约定）。
 */
interface MessageApi {

    /** 消息列表：公告与推送共用，靠 `kind` 区分。 */
    @GET(ApiPaths.MESSAGES)
    suspend fun messages(
        @Query("page") page: Int = 1,
        @Query("size") size: Int = DEFAULT_PAGE_SIZE,
    ): ApiEnvelopeDto<MessageListDto>

    /** 批量标记已读。 */
    @POST(ApiPaths.MESSAGE_READ)
    suspend fun markRead(@Body body: MessageReadDto): ApiEnvelopeDto<Unit>

    /** 最新版本；`code = 0` 表示后端没配，客户端跳过检查。 */
    @GET(ApiPaths.VERSION)
    suspend fun version(): ApiEnvelopeDto<VersionDto>

    companion object {
        /** 一页 20 条：消息都是短文本，翻页成本低。 */
        const val DEFAULT_PAGE_SIZE = 20
    }
}
