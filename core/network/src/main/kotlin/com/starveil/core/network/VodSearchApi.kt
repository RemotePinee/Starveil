package com.starveil.core.network

import com.starveil.core.network.dto.ApiEnvelopeDto
import com.starveil.core.network.dto.PageDto
import com.starveil.core.network.dto.VodDto
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 搜索接口。单独一个接口而不是塞进 [StarveilApi]：后者被十几个测试 fake 实现，
 * 加一个方法就要改一圈。
 */
interface VodSearchApi {

    /**
     * 关键词搜索走 `v1.vod?wd=`，与列表接口同一个路由，只多一个参数。
     *
     * [type] 是分类号：剧场内的关键词搜索靠它把结果收窄到短剧分类，null 表示不限分类
     * （加这个参数之前的行为）。放在最后并带默认值，老调用点一个都不用改。
     */
    @GET(ApiPaths.VOD_LIST)
    suspend fun search(
        @Query("wd") keyword: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20,
        @Query("type") type: Int? = null,
    ): ApiEnvelopeDto<PageDto<VodDto>>
}
