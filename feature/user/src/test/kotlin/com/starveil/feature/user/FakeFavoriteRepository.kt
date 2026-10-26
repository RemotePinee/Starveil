package com.starveil.feature.user

import com.starveil.core.common.AppResult
import com.starveil.core.data.FavoriteRepository
import com.starveil.core.model.FavoriteItem

/**
 * 收藏仓库替身：按页码返回预设结果，并记录请求过的页码。
 *
 * [pages] 故意留成可变字典，这样"重试"能用同一个 ViewModel 换一页结果；
 * 未预设的页码返回空列表，相当于后端翻到底了。
 */
internal class FakeFavoriteRepository(
    val pages: MutableMap<Int, AppResult<List<FavoriteItem>>> = mutableMapOf(),
    var removeResult: AppResult<Unit> = AppResult.Success(Unit),
    /** 首帧快照替身：默认没有，等价"这台设备还没存过"的那一份。 */
    private val cached: List<FavoriteItem>? = null,
) : FavoriteRepository {

    val requestedPages: MutableList<Int> = mutableListOf()

    var removeCalls: Int = 0
        private set

    override fun cachedFavorites(): List<FavoriteItem>? = cached

    override suspend fun list(page: Int, limit: Int): AppResult<List<FavoriteItem>> {
        requestedPages += page
        return pages[page] ?: AppResult.Success(emptyList())
    }

    override suspend fun add(vodId: Int): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun remove(vodIds: List<Int>): AppResult<Unit> {
        removeCalls += 1
        return removeResult
    }
}

/** 一条收藏样例，字段取值参考 `core/network/src/test/resources/fixtures/v1_user_favs.json`。 */
internal fun favoriteItem(
    vodId: Int,
    ulogId: Int = vodId,
    title: String = "示例影片 $vodId",
    createdAt: Long = 1_560_877_169L,
): FavoriteItem = FavoriteItem(
    ulogId = ulogId,
    moduleId = 1,
    ulogType = 2,
    vodId = vodId,
    sid = 0,
    nid = 0,
    points = 0,
    createdAt = createdAt,
    userName = "demo",
    title = title,
    poster = "https://img.example.com/a.jpg",
    link = "/index.php/vod/detail/id/$vodId.html",
    categoryName = "综艺",
)
