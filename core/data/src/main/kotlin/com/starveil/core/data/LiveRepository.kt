package com.starveil.core.data

import com.starveil.core.common.AppResult
import com.starveil.core.common.map
import com.starveil.core.model.LiveChannel
import com.starveil.core.network.StarveilApi
import com.starveil.core.network.dto.LivePageDto
import com.starveil.core.network.mapper.toDomain
import kotlinx.serialization.serializer

/** 直播分类：`name` 为空串表示「全部」。 */
data class LiveGroup(
    val name: String,
    val count: Int,
)

/**
 * 一页直播频道。
 *
 * [total] 是**全量**条数（不是本页条数），标题里那个"共 N 路"要用它，
 * 否则只会显示当前已加载的数量。[hasMore] 已按 total 算好。
 */
data class LivePage(
    val items: List<LiveChannel>,
    val total: Int,
    val groups: List<LiveGroup>,
    val hasMore: Boolean,
)

/** 每页条数：后端 `v1.zhibo` 默认 20、上限 60，这里取 30。 */
const val LIVE_PAGE_SIZE = 30

/**
 * 直播列表。
 *
 * 旧直播页没有详情接口，点开频道直接用 [LiveChannel.url] 起播。
 * 后端 `v1.zhibo` 一直支持 `page`/`limit`（`LiveController::getIndex`），
 * 早期客户端没传，所以只拿得到第一页 20 条——后台导了几百个频道也只显示 20 个。
 */
interface LiveRepository {

    suspend fun list(
        page: Int = 1,
        limit: Int = LIVE_PAGE_SIZE,
        group: String = "",
    ): AppResult<LivePage>

    /**
     * 首帧快照：上一次成功拿到的「全部」分类第一页（最多一条）。
     *
     * 默认没有——其它模块的测试 fake 不必为此补实现；[DefaultLiveRepository]
     * 返回注入进来的那一份。[LiveViewModel] 构造时同步读它铺首屏，避免进来先整屏转圈。
     */
    fun cachedLivePages(): List<LivePage>? = null
}

class DefaultLiveRepository(
    private val api: StarveilApi,
    /** 首帧快照；为 null 时不落盘（测试与其它入口构造本类时不必提供）。 */
    private val snapshot: PageSnapshotStore? = null,
) : LiveRepository {

    override fun cachedLivePages(): List<LivePage>? =
        snapshot?.read(PageSnapshotStore.KEY_LIVE, serializer<LivePageDto>())
            ?.takeIf { dto -> dto.list.isNotEmpty() }
            ?.let { dto -> listOf(dto.toLivePage(pageSize = dto.limit)) }

    override suspend fun list(page: Int, limit: Int, group: String): AppResult<LivePage> =
        apiCall { api.liveList(page = page, limit = limit, group = group) }
            .requireData()
            .map { dto ->
                saveFirstPage(dto, page = page, group = group)
                dto.toLivePage(pageSize = limit)
            }

    /**
     * 只存「全部」分类的第一页：切到某个分类后列表是另一份数据，
     * 拿来铺首屏会张冠李戴。写入非 suspend，[SnapshotStore] 内部切 IO 线程。
     */
    private fun saveFirstPage(dto: LivePageDto, page: Int, group: String) {
        if (page != 1 || group.isNotEmpty()) return
        // 后台把频道删光了：旧快照必须作废。留着它，冷启动就会把已删的频道再铺一遍
        if (dto.list.isEmpty()) {
            snapshot?.remove(PageSnapshotStore.KEY_LIVE)
            return
        }
        snapshot?.write(PageSnapshotStore.KEY_LIVE, serializer<LivePageDto>(), dto)
    }

    /** [pageSize] 是请求时的 limit：快照里存的 limit 可能与当前分页参数不同。 */
    private fun LivePageDto.toLivePage(pageSize: Int): LivePage = LivePage(
        items = list.map { item -> item.toDomain() },
        total = total,
        groups = groups.map { group -> LiveGroup(name = group.group, count = group.count) },
        // total 为 0 说明后端没返回总数，用"本页是否装满"兜底
        hasMore = if (total > 0) page * pageSize < total else list.size >= pageSize,
    )
}
