package com.starveil.core.data

import com.starveil.core.common.AppResult
import com.starveil.core.common.map
import com.starveil.core.common.onSuccess
import com.starveil.core.model.RankCatalog
import com.starveil.core.model.Vod
import com.starveil.core.network.StarveilApi
import com.starveil.core.network.dto.RankBoardDto
import com.starveil.core.network.dto.RankCatalogDto
import com.starveil.core.network.mapper.toDomain
import kotlinx.serialization.serializer

/**
 * 榜单，两个接口两条契约（旧 与 ）：
 *
 * - [board] → `v1.vod/vodphb`，平铺列表，要传排序串与分类 id；
 * - [catalog] → `v1.vod/vodPhbAll`，无参数，返回按分类分行的目录。
 *
 * 两者返回结构不同（有无 total、有无分类层），不要合并成一个方法。
 */
interface RankRepository {

    /**
     * @param orderBy 带方向的排序串，如 `vod_hits desc`
     * @param type 分类 id，旧页面从 Tab 参数传入
     * @param page 从 1 开始，旧页面每次上拉 +1
     */
    suspend fun board(
        orderBy: String,
        type: Int,
        page: Int,
        limit: Int = DEFAULT_RANK_LIMIT,
    ): AppResult<List<Vod>>

    suspend fun catalog(): AppResult<RankCatalog>

    /**
     * 上次成功响应的榜单列表快照（冷启动首帧用）。
     *
     * 给默认实现：其它模块手写的 fake 不必为快照额外实现一遍。
     */
    fun cachedRankBoard(): List<Vod>? = null

    /** 上次成功响应的榜单目录快照；默认没有。 */
    fun cachedRankCatalog(): RankCatalog? = null
}

class DefaultRankRepository(
    private val api: StarveilApi,
    /**
     * 榜单首帧快照；为 null 时不落盘（测试与其它入口构造本类时不必提供）。
     * 写入点都在接口成功分支里：失败不覆盖快照——上一次的好数据比这次的错误更有用。
     */
    private val snapshot: PageSnapshotStore? = null,
) : RankRepository {

    override fun cachedRankBoard(): List<Vod>? = snapshot?.vods(PageSnapshotStore.KEY_RANK_BOARD)

    override fun cachedRankCatalog(): RankCatalog? =
        snapshot?.read(PageSnapshotStore.KEY_RANK_CATALOG, serializer<RankCatalogDto>())?.toDomain()

    override suspend fun board(
        orderBy: String,
        type: Int,
        page: Int,
        limit: Int,
    ): AppResult<List<Vod>> =
        apiCall { api.vodRank(orderBy = orderBy, type = type, page = page, limit = limit) }
            .requireData()
            .onSuccess { dto -> saveBoardSnapshot(page = page, dto = dto) }
            .map { board -> board.toDomain() }

    override suspend fun catalog(): AppResult<RankCatalog> =
        apiCall { api.vodRankAll() }
            .requireData()
            .onSuccess { dto ->
                snapshot?.write(PageSnapshotStore.KEY_RANK_CATALOG, serializer<RankCatalogDto>(), dto)
            }
            .map { catalog -> catalog.toDomain() }

    /**
     * 只存第一页：翻页存的是半截列表，铺不出完整首屏。
     *
     * 不按分类过滤：榜单首个分类的 typeId 从 1 起，写死 `type == 0` 等于永远不存。
     * 代价是首帧可能显示上次浏览的那个分类，网络回来后原地覆盖，比先转圈好。
     */
    private fun saveBoardSnapshot(page: Int, dto: RankBoardDto) {
        if (page != SNAPSHOT_PAGE) return
        snapshot?.saveVods(PageSnapshotStore.KEY_RANK_BOARD, dto.list)
    }
}

/** 快照只认第一页的响应，见 [DefaultRankRepository.saveBoardSnapshot]。 */
private const val SNAPSHOT_PAGE = 1

/** 旧榜单页固定每页 20。 */
private const val DEFAULT_RANK_LIMIT = 20
