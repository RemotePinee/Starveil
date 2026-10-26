package com.starveil.core.network

import com.starveil.core.network.dto.ApiEnvelopeDto
import com.starveil.core.network.dto.PointsTaskListDto
import com.starveil.core.network.dto.ScoreDto
import com.starveil.core.network.dto.ShareInfoDto
import com.starveil.core.network.dto.SignStateDto
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * 积分中心：签到 + 今日任务 + 分享奖励。
 *
 * 独立接口而不是塞进 [StarveilApi]：后者被十几个测试 fake 实现，每加一个方法都要改一圈
 * （与 [HomeContentApi] / [ProgressListApi] 同一约定）。
 *
 * 后端口径见 `server/starveilapi/src/Controller/UserController.php`：
 * `v1.user/sign` 是 GET 查状态、POST 签到，同一路径两种动词。
 */
interface PointsApi {

    /** 签到状态（`v1.user/sign` GET）。 */
    @GET(ApiPaths.SIGN)
    suspend fun signState(): ApiEnvelopeDto<SignStateDto>

    /** 签到（同一路径 POST），返回本次获得的积分。 */
    @POST(ApiPaths.SIGN)
    suspend fun signIn(): ApiEnvelopeDto<ScoreDto>

    /** 今日任务列表（`v1.user/task` GET）。 */
    @GET(ApiPaths.TASK_LIST)
    suspend fun pointsTasks(): ApiEnvelopeDto<PointsTaskListDto>

    /** 领取任务奖励（`v1.user/task` POST），目前后端只认 `watch`。 */
    @FormUrlEncoded
    @POST(ApiPaths.TASK_LIST)
    suspend fun claimPointsTask(@Field("key") key: String): ApiEnvelopeDto<ScoreDto>

    /** 分享信息（`v1.user/shareInfo` GET）：标题、描述、落地链接由后台配置。 */
    @GET(ApiPaths.SHARE_INFO)
    suspend fun shareInfo(): ApiEnvelopeDto<ShareInfoDto>

    /** 分享奖励（`v1.user/shareScore` POST），每天首次有效。 */
    @POST(ApiPaths.SHARE_SCORE)
    suspend fun shareScore(): ApiEnvelopeDto<ScoreDto>
}
