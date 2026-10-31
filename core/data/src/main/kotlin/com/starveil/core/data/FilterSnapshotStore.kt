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
 *
 * **懒加载**：本类在 `AppContainer`（即 `Application.onCreate`）里构造，属性初始化就
 * `read()` 会把读文件 + 反序列化压到冷启动首帧之前。改成第一次取内容时才读盘。
 */
class FilterSnapshotStore(private val store: SnapshotStore) {

    /**
     * 进程内副本；null = 还没读过盘。
     *
     * `@Volatile`：[saveTypes]/[saveItems] 在协程（IO 线程）里写，[snapshot] 在首帧主线程
     * 裸读；写侧的 `@Synchronized` 不建立读的 happens-before。
     */
    @Volatile
    private var dto: FilterCacheDto? = null

    /** 有结果才算可用：只有分类条、没有片子时仍然是"空页"，不如直接转圈。 */
    fun snapshot(): FilterSnapshot? {
        val current = loaded()
        if (current.items.isEmpty()) return null
        return FilterSnapshot(
            types = current.types.map { it.toDomain() },
            items = current.items.map { it.toDomain() },
        )
    }

    @Synchronized
    fun saveTypes(list: List<VodTypeDto>) {
        val current = loaded()
        if (list.isEmpty() || list == current.types) return
        persist(current.copy(types = list))
    }

    @Synchronized
    fun saveItems(list: List<VodDto>) {
        val current = loaded()
        if (list.isEmpty() || list == current.items) return
        persist(current.copy(items = list))
    }

    /** 先落内存再落盘：盘写失败最多是下次冷启动少一份首帧，内存里的这次已经算数。 */
    private fun persist(next: FilterCacheDto) {
        dto = next
        store.write(NAME, serializer<FilterCacheDto>(), next)
    }

    /** 取内存副本；没有才读盘，且只读一次（重入 `@Synchronized` 无碍）。 */
    private fun loaded(): FilterCacheDto = dto ?: readFromDisk()

    @Synchronized
    private fun readFromDisk(): FilterCacheDto {
        dto?.let { return it }
        val restored = store.read(NAME, serializer<FilterCacheDto>()) ?: FilterCacheDto()
        dto = restored
        return restored
    }

    private companion object {
        const val NAME = "filter_feed"
    }
}
