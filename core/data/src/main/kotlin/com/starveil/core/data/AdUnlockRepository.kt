package com.starveil.core.data

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.common.map
import com.starveil.core.model.AdSession
import com.starveil.core.network.StarveilApi
import com.starveil.core.network.mapper.toDomain

/**
 * 直投激励视频解锁：领会话 → 播 → 报进度 → 完成。
 *
 * 三步的顺序是契约的一部分，跳过任何一步都会被服务端拒绝：
 * 1. [startSession] 领 token，服务端记下 vod + 集号 + 开始时间；
 * 2. [reportProgress] 播放期间每 5 秒报一次播放位置；
 * 3. [complete] 播完才调，服务端校验进度与真实经过时间后**自己**写解锁记录。
 *
 * 客户端不做任何本地"已解锁"标记，[complete] 返回 true 只代表服务端认了账，
 * 调用方仍要重查 `checkVodTrySee` 拿最新权限。
 *
 * 失败一律收敛成 [AppResult.Failure]，不抛异常（AGENTS.md 第 5 节）。
 */
interface AdUnlockRepository {

    /**
     * 领一次观看会话。
     *
     * 默认实现返回失败：其它模块手写的 fake 不必跟着补方法，也不会误报"已解锁"。
     */
    suspend fun startSession(vodId: Int, episodeNumber: Int): AppResult<AdSession> = unsupported()

    /** 上报播放位置（秒）。只记进度，不解锁。 */
    suspend fun reportProgress(token: String, positionSec: Int): AppResult<Unit> = unsupported()

    /** 播完才调；true 表示服务端已写入本集解锁记录。 */
    suspend fun complete(token: String): AppResult<Boolean> = unsupported()
}

class DefaultAdUnlockRepository(private val api: StarveilApi) : AdUnlockRepository {

    override suspend fun startSession(vodId: Int, episodeNumber: Int): AppResult<AdSession> =
        apiCall { api.adSession(vodId = vodId.toString(), episodeNumber = episodeNumber.toString()) }
            .requireData()
            .map { dto -> dto.toDomain() }

    override suspend fun reportProgress(token: String, positionSec: Int): AppResult<Unit> =
        apiCall { api.adProgress(token = token, positionSec = positionSec.coerceAtLeast(0)) }
            .requireSuccess()

    override suspend fun complete(token: String): AppResult<Boolean> =
        apiCall { api.adComplete(token = token) }
            .requireData()
            .map { dto -> dto.unlocked == UNLOCKED }
}

/** 服务端"已写解锁记录"的取值；其它值（含缺字段）都按未解锁处理。 */
private const val UNLOCKED = 1

/**
 * 默认实现必须明确失败。
 *
 * 用 Business 而不是 Unknown：这不是传输层故障，UI 不该弹"网络异常，请重试"，
 * 而应把它当成"这个入口在当前实现里不可用"。
 */
private fun unsupported(): AppResult.Failure =
    AppResult.Failure(AppError.Business(code = 0, message = "当前实现不支持激励视频解锁"))
