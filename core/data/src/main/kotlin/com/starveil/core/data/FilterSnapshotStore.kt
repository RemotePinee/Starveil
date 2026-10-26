package com.starveil.core.data

import com.starveil.core.model.Vod
import com.starveil.core.model.VodType
import com.starveil.core.network.dto.VodDto
import com.starveil.core.network.dto.VodTypeDto
import com.starveil.core.network.mapper.toDomain
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/** 筛选页首帧要用的两份数据：分类维度（决定筛选条）与上一次的默认结果。 */
class FilterSnapshot(
    val types: List<VodType>,
    val items: List<Vod>,
)

@Serializable
private data class FilterCacheDto(
    val types: List<VodTypeDto> = emptyList(),
    val items: List<VodDto> = emptyList(),
)

/**
 * 筛选页快照：只存「默认条件」（分类=全部、无地区/年份/语言/排序）的第一页。
 *
 * 为什么不连筛选条件一起存：用户点进「筛选」期望的是从头挑，回到上次那一组条件
 * 反而是惊喜不是需求；而默认条件的首屏正好是他每次进来第一眼看到的那一屏。
 */
class FilterSnapshotStore(private val store: SnapshotStore) {

    private var dto: FilterCacheDto = store.read(NAME, serializer<FilterCacheDto>()) ?: FilterCacheDto()

    /** 有结果才算可用：只有分类条、没有片子时仍然是"空页"，不如直接转圈。 */
    fun snapshot(): FilterSnapshot? = if (dto.items.isEmpty()) {
        null
    } else {
        FilterSnapshot(
            types = dto.types.map { it.toDomain() },
            items = dto.items.map { it.toDomain() },
        )
    }

    @Synchronized
    fun saveTypes(list: List<VodTypeDto>) {
        if (list.isEmpty() || list == dto.types) return
        dto = dto.copy(types = list)
        persist()
    }

    @Synchronized
    fun saveItems(list: List<VodDto>) {
        if (list.isEmpty() || list == dto.items) return
        dto = dto.copy(items = list)
        persist()
    }

    private fun persist() = store.write(NAME, serializer<FilterCacheDto>(), dto)

    private companion object {
        const val NAME = "filter_feed"
    }
}
