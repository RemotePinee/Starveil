package com.starveil.core.network

import com.starveil.core.network.dto.ApiEnvelopeDto
import com.starveil.core.network.dto.VodTypeListDto
import retrofit2.http.GET

/**
 * 分类接口。
 *
 * 单独一个接口而不是塞进 [StarveilApi]：`StarveilApi` 被十几个测试 fake 实现，
 * 每加一个方法都要改一遍。分类只有首页用，独立接口更省事。
 */
interface VodTypeApi {

    /** `v1.vod/types`。 */
    @GET(ApiPaths.VOD_TYPES)
    suspend fun vodTypes(): ApiEnvelopeDto<VodTypeListDto>
}
