package com.starveil.core.data

import com.starveil.core.model.Vod
import com.starveil.core.network.dto.VodDto
import com.starveil.core.network.mapper.toDomain
import kotlinx.serialization.serializer

/**
 * 详情页快照：按 vodId 存最近看过的整份 `VodDto`（含播放源与剧集）。
 *
 * 为什么整份存而不是只存摘要：详情页要有选集才像"打开了"，只存标题海报反而会
 * 出现"进去了但选集是空的"。DTO 已经是 `@Serializable`，落地成本就是一次文件写。
 */
class DetailSnapshotStore(private val store: SnapshotStore) {

    fun load(vodId: Int): Vod? = store.read(name(vodId), serializer<VodDto>())?.toDomain()

    fun save(vodId: Int, dto: VodDto) {
        store.write(name(vodId), serializer<VodDto>(), dto)
        // 每部片一个文件：只留最近打开过的一批，避免无限增长（几十 KB × N）
        store.prune(prefix = PREFIX, keep = KEEP)
    }

    private fun name(vodId: Int) = "$PREFIX$vodId"

    private companion object {
        const val PREFIX = "detail_"
        const val KEEP = 30
    }
}
