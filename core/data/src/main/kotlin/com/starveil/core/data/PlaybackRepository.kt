package com.starveil.core.data

import com.starveil.core.common.AppResult
import com.starveil.core.common.map
import com.starveil.core.model.VideoProgress
import com.starveil.core.network.ProgressListApi
import com.starveil.core.network.StarveilApi

/**
 * 播放相关的写操作。
 *
 * 两件事分开：
 * - [reportWatchedSeconds]：观看时长，进 `mac_user.view_seconds`（积分 / 任务用）；
 * - [saveProgress] / [progressOf]：播放位置，进 `cuican_vlog`（观看记录 + 断点续播用）。
 *
 * 只上报时长、没上报位置，结果是「看过的不进观看记录、也续不上」。
 */
interface PlaybackRepository {

    /** 上报本次观看的真实秒数（不是累计值）。 */
    suspend fun reportWatchedSeconds(seconds: Long): AppResult<Unit>

    /** 保存播放位置（秒）。失败不影响播放。 */
    suspend fun saveProgress(
        vodId: Int,
        sourceIndex: Int,
        episodeIndex: Int,
        positionSec: Int,
    ): AppResult<Unit>

    /** 读某部影片的断点；没看过时返回全 0。 */
    suspend fun progressOf(vodId: Int): AppResult<VideoProgress>
}

class DefaultPlaybackRepository(
    private val api: StarveilApi,
    private val progressApi: ProgressListApi,
) : PlaybackRepository {

    override suspend fun reportWatchedSeconds(seconds: Long): AppResult<Unit> {
        if (seconds <= 0) return AppResult.Success(Unit)
        return apiCall { api.addWatchTime(seconds.toInt()) }.requireSuccess()
    }

    override suspend fun saveProgress(
        vodId: Int,
        sourceIndex: Int,
        episodeIndex: Int,
        positionSec: Int,
    ): AppResult<Unit> {
        if (vodId <= 0 || positionSec < 0) return AppResult.Success(Unit)
        return apiCall {
            progressApi.saveProgress(vodId, sourceIndex, episodeIndex, positionSec)
        }.requireSuccess()
    }

    override suspend fun progressOf(vodId: Int): AppResult<VideoProgress> {
        if (vodId <= 0) return AppResult.Success(VideoProgress())
        return apiCall { progressApi.progressOf(vodId) }
            .requireData()
            .map { dto ->
                VideoProgress(
                    sourceIndex = dto.sourceIndex,
                    episodeIndex = dto.episodeIndex,
                    positionSec = dto.progressSec,
                )
            }
    }
}
