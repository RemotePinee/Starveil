package com.starveil.core.data

import com.starveil.core.common.AppResult
import com.starveil.core.common.map
import com.starveil.core.model.TrySeeGate
import com.starveil.core.network.StarveilApi
import com.starveil.core.network.mapper.toDomain

/**
 * 付费墙判定（调用点 ）。
 *
 * 服务端已改成单一开关：只回答"要不要解锁"（`status`）与剩余免费次数，
 * `trysee` 秒数已废弃。[TrySeeGate.access] 由 `TrySeeRules.access` 统一折算，
 * 播放器不要再自己写一套判定。
 */
interface AccessRepository {

    /**
     * @param vodId 影片 id（旧 query `id`）
     * @param episodeNumber 单集 nid，1 起（旧 query `nid`）
     * @param sourceIndex 播放源数组下标，仅用于调用方定位，**不发往后端**：见实现里的说明
     */
    suspend fun checkTrySee(vodId: Int, episodeNumber: Int, sourceIndex: Int): AppResult<TrySeeGate>
}

class DefaultAccessRepository(private val api: StarveilApi) : AccessRepository {

    override suspend fun checkTrySee(
        vodId: Int,
        episodeNumber: Int,
        sourceIndex: Int,
    ): AppResult<TrySeeGate> {
        // sourceIndex 不参与请求：旧契约只有 id / nid / mid 三个 query，mid 恒为模块 id "1"，
        // 与第几源无关。保留形参是为了调用方拿的是
        // 「源 + 集」这一对下标时，不必自己拆开再传。
        return apiCall {
            api.checkVodTrySee(vodId = vodId.toString(), episodeNumber = episodeNumber.toString())
        }.requireData().map { gate -> gate.toDomain() }
    }
}
