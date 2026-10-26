package com.starveil.core.network

import com.starveil.core.network.dto.ApiEnvelopeDto
import com.starveil.core.network.dto.HomeBannerListDto
import com.starveil.core.network.dto.HomeBlockListDto
import com.starveil.core.network.dto.HomeLinkListDto
import com.starveil.core.network.dto.HomePushDto
import com.starveil.core.network.dto.HomeSpotlightDto
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 首页内容位接口。独立接口而不是塞进 [StarveilApi]：后者被十几个测试 fake 实现，
 * 每加一个方法都要改一圈（与 [VodSearchApi] 同一约定）。
 */
interface HomeContentApi {

    @GET(ApiPaths.BANNER)
    suspend fun banners(): ApiEnvelopeDto<HomeBannerListDto>

    @GET(ApiPaths.CATEGORY)
    suspend fun blocks(@Query("need_vod") needVod: Int = 1): ApiEnvelopeDto<HomeBlockListDto>

    @GET(ApiPaths.LINKS)
    suspend fun links(): ApiEnvelopeDto<HomeLinkListDto>

    /** 首页「编辑精选」：单对象壳，不是 list。 */
    @GET(ApiPaths.FEATURED)
    suspend fun featured(): ApiEnvelopeDto<HomeSpotlightDto>

    /** 首页「热度榜」：`type_id` 区分分类，0 = 全局。 */
    @GET(ApiPaths.HOT)
    suspend fun hot(@Query("type_id") typeId: Int = 0): ApiEnvelopeDto<HomeSpotlightDto>

    /** 首页「猜你喜欢」：`type_id` 同上。 */
    @GET(ApiPaths.GUESS)
    suspend fun guess(@Query("type_id") typeId: Int = 0): ApiEnvelopeDto<HomeSpotlightDto>

    /** 每日推荐：只下发条数，用于顶栏红点。 */
    @GET(ApiPaths.PUSH)
    suspend fun push(): ApiEnvelopeDto<HomePushDto>
}
