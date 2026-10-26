package com.starveil.feature.home

import com.starveil.core.common.AppResult
import com.starveil.core.model.Vod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/**
 * 分类列表的内存缓存 + 在飞请求去重 + 后台预热。
 *
 * 单独成类有两个原因：`HomeViewModel` 已经贴着文件行数上限（AGENTS.md §2），
 * 而「哪个分类有内容、哪个还在请求、谁该预热」跟页面状态无关，抽出来还能单独测。
 *
 * 线程约定：只在主线程调用（`viewModelScope` 默认调度器），所以内部用普通 MutableMap，
 * 不需要加锁；`fetch` 里的真实网络请求由仓库自己切到 IO。
 */
internal class HomeFeedCache(
    private val scope: CoroutineScope,
    private val fetch: suspend (typeId: Int) -> AppResult<List<Vod>>,
) {

    private val items = mutableMapOf<Int, List<Vod>>()

    private val inflight = mutableMapOf<Int, Deferred<AppResult<List<Vod>>>>()

    private var prefetchJob: Job? = null

    /** 已经拿到的列表；null 表示这个分类还没加载过（调用方据此决定要不要上骨架）。 */
    fun cached(typeId: Int): List<Vod>? = items[typeId]

    /**
     * 取某分类的列表，成功就写进缓存。
     *
     * 同一个分类同一时间只会有一个请求在飞：预热刚发出去、用户立刻切过来时，
     * 两次调用共享同一个结果，不会对同一分类打两次接口。
     */
    suspend fun load(typeId: Int): AppResult<List<Vod>> {
        val result = request(typeId).await()
        if (result is AppResult.Success) {
            items[typeId] = result.value
        }
        return result
    }

    /**
     * 后台预热：每批 [batch] 个并发，各自写进缓存。
     *
     * 调用方不必等它——「进首页无阻塞加载其它分类」就是靠这里；失败就跳过，
     * 用户真切过去时再按需请求一次。
     */
    fun prefetch(typeIds: List<Int>, batch: Int = PREFETCH_BATCH) {
        val ids = typeIds.filter { !items.containsKey(it) }.distinct()
        if (ids.isEmpty()) return
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            ids.chunked(batch).forEach { chunk ->
                chunk.map { id -> async { load(id) } }.awaitAll()
            }
        }
    }

    private fun request(typeId: Int): Deferred<AppResult<List<Vod>>> {
        inflight[typeId]?.let { return it }
        val job = scope.async {
            try {
                fetch(typeId)
            } finally {
                // 请求结束就摘掉：失败的分类下次切过去要能重试
                inflight.remove(typeId)
            }
        }
        inflight[typeId] = job
        return job
    }

    private companion object {
        /** 预取并发：一批 3 个，一进首页就打十几个请求会把带宽和电量一起吃掉。 */
        const val PREFETCH_BATCH = 3
    }
}
