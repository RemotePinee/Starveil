package com.starveil.core.network

import com.starveil.core.network.dto.CmsDanmakuListDto
import com.starveil.core.network.dto.CmsDanmakuSendDto
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * 苹果CMS 内置弹幕接口。
 *
 * 路径以站点根为基准：`/api.php/danmaku/get_list`、`/api.php/danmaku/send`。
 * 与 `v1.*` 那套接口不是同一个 baseUrl（那个带 `/icciu_api.php/` 前缀），
 * 所以由 [ApiClient] 单独建一个 Retrofit。
 *
 * 成功码是 `code == 1`。
 */
interface CmsDanmakuApi {

    @GET("danmaku/get_list")
    suspend fun list(
        @Query("vod_id") vodId: Int,
        @Query("sid") sid: Int,
        @Query("nid") nid: Int,
        @Query("limit") limit: Int = 2000,
    ): CmsDanmakuListDto

    /**
     * 发送弹幕。字段用 Map 传：`vod_id/sid/nid/time/text/type/color`。
     * `time` 是秒（可带小数），由调用方从毫秒换算。
     */
    @FormUrlEncoded
    @POST("danmaku/send")
    suspend fun send(@FieldMap fields: Map<String, String>): CmsDanmakuSendDto
}
