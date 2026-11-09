package com.starveil.feature.home

import com.starveil.core.common.AppResult
import com.starveil.core.data.VodRepository
import com.starveil.core.model.HomeSpotlight
import com.starveil.core.model.Vod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 分类 → (热度榜, 猜你喜欢) 的缓存。
 *
 * 两条路共用一份缓存：切 Tab 时 [load] 要被等着（数据没到就上骨架），
 * 进首页时 [prefetch] 在后台逐个补齐其它分类。同一个分类不会因为两条路同时走而打两次接口
 * ——两条路都先查缓存，只是并发撞上时可能各发一次，和以前的 `spotlightsOf` 一致，可以接受。
 *
 * [onLoaded] 把拿到的卡片位交给封面预热队列：首页正文里真正上屏的常常是这两块的片，
 * 而不是分类列表本身，不预热它们等于没预热。
 */
internal class HomeSpotlightCache(
    private val scope: CoroutineScope,
    private val repository: VodRepository,
    private val onLoaded: (List<Vod>) -> Unit = {},
) {

    private val cache = mutableMapOf<Int, Pair<HomeSpotlight, HomeSpotlight>>()

    operator fun get(typeId: Int): Pair<HomeSpotlight, HomeSpotlight>? = cache[typeId]

    operator fun set(typeId: Int, spots: Pair<HomeSpotlight, HomeSpotlight>) {
        cache[typeId] = spots
    }

    /** 取某分类的两个卡片位。失败/未配置都落成空配置，不把错误抛给 UI。 */
    suspend fun load(typeId: Int): Pair<HomeSpotlight, HomeSpotlight> {
        cache[typeId]?.let { return it }
        val hot = (repository.hotByType(typeId) as? AppResult.Success)?.value ?: HomeSpotlight()
        val guess = (repository.guessByType(typeId) as? AppResult.Success)?.value ?: HomeSpotlight()
        onLoaded(hot.items)
        onLoaded(guess.items)
        return (hot to guess).also { cache[typeId] = it }
    }

    /** 后台逐个预热，失败就地落空配置；调用方不必等它。 */
    fun prefetch(typeIds: List<Int>) {
        scope.launch {
            typeIds.forEach { typeId -> load(typeId) }
        }
    }
}
