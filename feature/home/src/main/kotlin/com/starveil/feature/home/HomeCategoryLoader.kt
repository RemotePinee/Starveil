package com.starveil.feature.home

import com.starveil.core.common.AppResult
import com.starveil.core.data.VodRepository
import com.starveil.core.model.HomeBanner
import com.starveil.core.model.HomeSpotlight
import com.starveil.core.model.Vod
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 切分类时的并发取数。
 *
 * 列表、热度榜/猜你喜欢、编辑精选三路一起发，任何一路先回来都不单独提交——
 * 分两路各写一次状态会让界面先空、再闪上一分类的榜、最后才落到本分类。
 *
 * 单独成类是职责切分：`HomeViewModel` 只负责"什么时候切、拿到后怎么写状态"，
 * 不负责"怎么并发取"。
 */
internal class HomeCategoryLoader(
    private val feed: HomeFeedCache,
    private val spotlights: HomeSpotlightCache,
    private val repository: VodRepository,
) {

    /** 三路取数的结果；[hero] 已经做过"空则回退全局"的口径统一之外的处理。 */
    data class Snapshot(
        val list: AppResult<List<Vod>>,
        val hot: HomeSpotlight,
        val guess: HomeSpotlight,
        val hero: HomeSpotlight,
        val banners: List<HomeBanner>,
    )

    private val byType = mutableMapOf<Int, List<HomeBanner>>()

    /** 命中缓存快速切换时用：不重新发请求。 */
    fun cachedBanners(typeId: Int): List<HomeBanner> = byType[typeId].orEmpty()

    fun rememberBanners(typeId: Int, banners: List<HomeBanner>) {
        byType[typeId] = banners
    }

    /**
     * 命中列表缓存快速切换时调用：只有还没拿过这个分类的轮播才去请求，
     * 回来后通过 [onLoaded] 交回给 ViewModel 写状态（不在这里持有 UI 状态）。
     */
    suspend fun ensureBanners(typeId: Int, onLoaded: (List<HomeBanner>) -> Unit) {
        if (!byType[typeId].isNullOrEmpty()) return
        val list = (repository.banners(typeId) as? AppResult.Success)?.value.orEmpty()
        if (list.isEmpty()) return
        byType[typeId] = list
        onLoaded(list)
    }

    suspend fun snapshot(typeId: Int): Snapshot = coroutineScope {
        val items = async { feed.load(typeId) }
        val spots = async { spotlights.load(typeId) }
        val hero = async { repository.featured(typeId) }
        val banners = async { repository.banners(typeId) }
        val pair = spots.await()
        Snapshot(
            list = items.await(),
            hot = pair.first,
            guess = pair.second,
            hero = (hero.await() as? AppResult.Success)?.value ?: HomeSpotlight(),
            banners = (banners.await() as? AppResult.Success)?.value.orEmpty(),
        ).also { byType[typeId] = it.banners }
    }
}
