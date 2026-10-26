package com.starveil.core.data

import com.starveil.core.common.AppResult
import com.starveil.core.model.ShortPlay

/**
 * 短剧数据来源。UI 只依赖本接口，不依赖 Retrofit 或 DTO。
 *
 * 短剧就是 `v1.vod` 里几个 `type_id` 的影片，后台可以配多个分类，所以每个方法都允许
 * 显式指定 [typeId]；不传时用后台配置的第一个分类（单分类时期的行为）。
 *
 * 加方法/加参数一律给默认值：其它 feature 的手写 fake 不必跟着改。
 */
interface ShortPlayRepository {

    /**
     * 剧场页分页列表。`page` 从 1 起，返回不足 `limit` 视为到底。
     *
     * [typeId] 为 null 表示"跟着后台配的第一个分类走"。
     */
    suspend fun feed(
        typeId: Int? = null,
        page: Int = 1,
        limit: Int = VodRepository.DEFAULT_PAGE_SIZE,
    ): AppResult<List<ShortPlay>>

    /**
     * 首帧快照：上一次成功拿到的该分类第一页。
     *
     * 默认没有——其它模块的测试 fake 不必为此补实现；[DefaultShortPlayRepository]
     * 返回注入进来的那一份。剧场页构造时同步读它铺首屏，避免进来先整屏转圈。
     */
    fun cachedShortPlay(typeId: Int? = null): List<ShortPlay>? = null
}
