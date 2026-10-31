package com.starveil.core.data

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.common.map
import com.starveil.core.model.FavoriteItem
import com.starveil.core.network.StarveilApi
import com.starveil.core.network.dto.FavoriteDto
import com.starveil.core.network.dto.PageDto
import com.starveil.core.network.mapper.toDomain
import kotlinx.serialization.serializer
import java.util.concurrent.ConcurrentHashMap

/**
 * 收藏。
 *
 * 三个接口的字段形态不一致，照搬别改：
 * - 列表 `GET v1.user/favs`：query `page` / `limit` / `ulog_type`
 * - 收藏 `POST v1.user/ulog`：表单 `mid` / `id` / `type`
 * - 取消 `DELETE v1.user/ulog`：query `ids`（影片 id 逗号串）/ `type`
 *
 * `type` / `ulog_type` 固定 2 表示影片类收藏。
 * 后端没有"切换"语义，客户端自己记当前是否已收藏，所以这里是 add / remove 两个方法。
 */
interface FavoriteRepository {

    /** @param limit 每页条数；列表页默认 10，收藏状态请用 [contains]。 */
    suspend fun list(page: Int, limit: Int = DEFAULT_PAGE_SIZE): AppResult<List<FavoriteItem>>

    suspend fun add(vodId: Int): AppResult<Unit>

    /** [vodIds] 拼成逗号串一次删完（旧  的做法）。 */
    suspend fun remove(vodIds: List<Int>): AppResult<Unit>

    /** 逐页查询收藏状态；达到扫描上限仍未查完时返回失败。 */
    suspend fun contains(vodId: Int): AppResult<Boolean> =
        scanFavorites(vodId) { page ->
            list(page = page, limit = FAVORITE_QUERY_LIMIT).map { items ->
                FavoritePage(items, hasMore = items.size >= FAVORITE_QUERY_LIMIT)
            }
        }

    /**
     * 首帧快照：上一次成功拿到的第一页。
     *
     * 默认没有——其它模块的测试 fake 不必为此补实现；[DefaultFavoriteRepository]
     * 返回注入进来的那一份。收藏页构造时同步读它铺首屏，避免进来先整屏转圈。
     * 登录/登出会整目录清快照（`PageSnapshotStore.clearAll()`），所以 key 不拼 userId。
     */
    fun cachedFavorites(): List<FavoriteItem>? = null

    /** 成功写入后的进程内状态，让仍驻留的播放器入口能立即同步收藏按钮。 */
    fun cachedFavoriteStatus(vodId: Int): Boolean? = null

    /**
     * 清掉进程内的收藏首帧缓存：内存副本 + 状态覆盖。
     *
     * 登录/登出必须调。只清磁盘（`PageSnapshotStore.clearAll()`）不够——内存这份
     * 在 `favoriteSnapshotLoaded` 置真后只读一次，不清就会在整个进程生命周期里
     * 继续返回上一个账号的收藏。默认空实现，其它模块的测试 fake 不必补。
     */
    fun resetLocalCache() {}
}

class DefaultFavoriteRepository(
    private val api: StarveilApi,
    /** 首帧快照；为 null 时不落盘（测试与其它入口构造本类时不必提供）。 */
    private val snapshot: PageSnapshotStore? = null,
) : FavoriteRepository {

    private var favoriteSnapshotCache: List<FavoriteDto>? = null

    /**
     * 进程内收藏状态覆盖：主线程（首帧、播放页收藏按钮）读，协程（[add]/[remove]）写。
     *
     * 普通 `mutableMapOf` 在这里不只是可见性问题——`HashMap` 并发写会改结构，
     * 读到一半可能死循环或丢键，所以用 [ConcurrentHashMap]。
     */
    private val favoriteStatusOverrides = ConcurrentHashMap<Int, Boolean>()

    override fun cachedFavoriteStatus(vodId: Int): Boolean? = favoriteStatusOverrides[vodId]
    private var favoriteSnapshotLoaded = false

    override fun cachedFavorites(): List<FavoriteItem>? =
        favoriteSnapshot()?.map { dto -> dto.toDomain() }

    private fun favoriteSnapshot(): List<FavoriteDto>? {
        if (!favoriteSnapshotLoaded) {
            // 用户自己的收藏不该因为放了一天就消失：过期靠登录/登出清空，不靠年龄
            favoriteSnapshotCache = snapshot?.read(
                PageSnapshotStore.KEY_FAVORITE,
                serializer<List<FavoriteDto>>(),
                SnapshotStore.KEEP_FOREVER,
            )
            favoriteSnapshotLoaded = true
        }
        return favoriteSnapshotCache
    }

    override fun resetLocalCache() {
        favoriteSnapshotCache = null
        favoriteSnapshotLoaded = false
        favoriteStatusOverrides.clear()
    }

    override suspend fun list(page: Int, limit: Int): AppResult<List<FavoriteItem>> =
        apiCall { api.favoriteList(page = page, limit = limit) }
            .requireData()
            .map { envelope ->
                if (page == FIRST_PAGE) updateFavoriteSnapshot(envelope.list)
                envelope.list.map { it.toDomain() }
            }

    /** 按响应壳分页，只读查询，不覆盖收藏页首帧快照。 */
    override suspend fun contains(vodId: Int): AppResult<Boolean> =
        scanFavorites(vodId) { page ->
            apiCall { api.favoriteList(page = page, limit = FAVORITE_QUERY_LIMIT) }
                .requireData()
                .map { envelope -> envelope.toFavoritePage(page) }
        }

    override suspend fun add(vodId: Int): AppResult<Unit> {
        val result = apiCall { api.favoriteAdd(vodId = vodId.toString()) }.requireSuccess()
        if (result is AppResult.Success) favoriteStatusOverrides[vodId] = true
        return result
    }

    override suspend fun remove(vodIds: List<Int>): AppResult<Unit> {
        val result = apiCall { api.favoriteRemove(ids = vodIds.joinToString(SEPARATOR)) }.requireSuccess()
        if (result is AppResult.Success) {
            vodIds.forEach { favoriteStatusOverrides[it] = false }
            val removedIds = vodIds.toSet()
            favoriteSnapshot()?.let { cached ->
                updateFavoriteSnapshot(cached.filterNot { it.toDomain().vodId in removedIds })
            }
        }
        return result
    }

    /** 首屏成功可缓存空结果；取消收藏成功后立即删除旧行，避免下次进入闪旧内容。 */
    private fun updateFavoriteSnapshot(list: List<FavoriteDto>) {
        favoriteSnapshotCache = list
        favoriteSnapshotLoaded = true
        snapshot?.write(PageSnapshotStore.KEY_FAVORITE, serializer<List<FavoriteDto>>(), list)
    }
}

/** 旧列表页固定每页 10 条。 */
private const val DEFAULT_PAGE_SIZE = 10

private const val SEPARATOR = ","

/** 快照只存第一页。 */
private const val FIRST_PAGE = 1

/** 后端收藏列表的每页上限（`UserController::getFavs` 的 `min(limit, 60)`）。 */
private const val FAVORITE_QUERY_LIMIT = 60

/** 扫描保护上限；到限仍有下一页时，收藏状态未知。 */
private const val MAX_FAVORITE_PAGES = 50

/** 一页收藏 + 是否还有下一页；两种响应形态各自换算，见 [scanFavorites]。 */
private data class FavoritePage(val items: List<FavoriteItem>, val hasMore: Boolean)

/** 逐页翻到找到 [vodId] 或翻完为止；两个实现共用同一套收尾规则。 */
private suspend fun scanFavorites(
    vodId: Int,
    fetch: suspend (Int) -> AppResult<FavoritePage>,
): AppResult<Boolean> {
    var page = FIRST_PAGE
    var verdict: AppResult<Boolean>? = null
    while (verdict == null && page <= MAX_FAVORITE_PAGES) {
        verdict = when (val fetched = fetch(page)) {
            is AppResult.Failure -> fetched
            is AppResult.Success -> pageVerdict(vodId, fetched.value)
        }
        page += 1
    }
    return verdict ?: AppResult.Failure(
        AppError.Unknown(IllegalStateException("收藏状态未知：已达到 $MAX_FAVORITE_PAGES 页查询上限")),
    )
}

/** 找到 → true；没有下一页 → false；否则 null，继续翻。 */
private fun pageVerdict(vodId: Int, page: FavoritePage): AppResult<Boolean>? = when {
    page.items.any { it.vodId == vodId } -> AppResult.Success(true)
    !page.hasMore -> AppResult.Success(false)
    else -> null
}

/** 有 total 时按原始收藏行数翻页，缺省时才按可见条数收尾。 */
private fun PageDto<FavoriteDto>.toFavoritePage(page: Int): FavoritePage {
    val size = limit.coerceIn(1, FAVORITE_QUERY_LIMIT)
    val hasMore = if (total > 0) page.toLong() * size < total else list.size >= size
    return FavoritePage(list.map { it.toDomain() }, hasMore = hasMore)
}
