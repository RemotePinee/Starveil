package com.starveil.feature.home

import com.starveil.core.model.HomeSpotlight

/**
 * 首页「编辑精选」按分类的状态。
 *
 * 后台 block 表本来就带 `type_id`（`Featured::payload($position, $typeId)`），
 * 之前客户端只取全局那份，切到动漫/短剧时头图还是电视剧。这里把"当前分类的精选 +
 * 各分类缓存 + 回退全局"收成一个小对象，避免 `HomeViewModel` 再涨字段（detekt 200 行上限）。
 */
internal class HomeFeaturedState {

    var current: HomeSpotlight = HomeSpotlight()
        private set

    private val byType = mutableMapOf<Int, HomeSpotlight>()

    /** 首屏/刷新拿到的全局精选：同时记为 0 号分类，供小分类回退。 */
    fun setGlobal(spotlight: HomeSpotlight) {
        current = spotlight
        byType[0] = spotlight
    }

    /** 分类结果：没配就回退全局，配了才覆盖当前值。 */
    fun apply(typeId: Int, spotlight: HomeSpotlight) {
        val resolved = if (spotlight.items.isEmpty() && typeId != 0) {
            byType[0] ?: current
        } else {
            spotlight
        }
        byType[typeId] = resolved
        current = resolved
    }

    /** 命中缓存快速切换时，回到该分类自己的那份。 */
    fun restore(typeId: Int) {
        current = byType[typeId] ?: current
    }
}
