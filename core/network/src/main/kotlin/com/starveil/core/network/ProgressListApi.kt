package com.starveil.core.network

import com.starveil.core.network.dto.ApiEnvelopeDto
import com.starveil.core.network.dto.VideoProgressDto
import com.starveil.core.network.dto.VideoProgressListDto
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * 观看进度读写。
 *
 * 独立接口而不是塞进 [StarveilApi]：后者被十几个测试 fake 实现，
 * 每加一个方法都要改一圈（与 [HomeContentApi] / [VodSearchApi] 同一约定）。
 *
 * 服务端：
 * - `GET  v1.vod/videoProgress?vod_id=` 读某部片的上次位置（`requireLogin`）；
 * - `POST v1.vod/videoProgress` 写位置，落 `cuican_vlog`，与观看记录同表；
 * - `GET  v1.user/videoProgressList` 最近 10 条，给「继续观看 / 观看记录」用。
 */
interface ProgressListApi {

    @GET(ApiPaths.VIDEO_PROGRESS_LIST)
    suspend fun recent(): ApiEnvelopeDto<VideoProgressListDto>

    /** 读某部影片的断点；没看过时服务端回 0/0/0，不是错误。 */
    @GET(ApiPaths.VOD_PROGRESS)
    suspend fun progressOf(@Query("vod_id") vodId: Int): ApiEnvelopeDto<VideoProgressDto>

    /**
     * 写播放位置。
     *
     * @param positionSec 当前播放位置（秒），不是累计观看时长
     */
    @FormUrlEncoded
    @POST(ApiPaths.VOD_PROGRESS)
    suspend fun saveProgress(
        @Field("vod_id") vodId: Int,
        @Field("source_index") sourceIndex: Int,
        @Field("episode_index") episodeIndex: Int,
        @Field("progress") positionSec: Int,
    ): ApiEnvelopeDto<String>
}
