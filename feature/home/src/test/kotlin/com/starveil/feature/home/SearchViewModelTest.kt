package com.starveil.feature.home

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.data.RankRepository
import com.starveil.core.data.SearchHistoryStore
import com.starveil.core.data.VodRepository
import com.starveil.core.model.RankCatalog
import com.starveil.core.model.Vod
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 搜索页状态测试：守历史与推荐两条新契约。
 *
 * 这里用 [StandardTestDispatcher] 而不是 Unconfined：去抖走的是虚拟时间，
 * 必须先断言「时间没到 → 没写历史」，再推进时钟看写一次。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val dispatcherRule = MainDispatcherRule(dispatcher)

    private val history = FakeSearchHistoryStore()

    @Test
    fun `typing does not write history before debounce`() = runTest(dispatcher.scheduler) {
        val repository = FakeSearchRepository()
        val viewModel = SearchViewModel(repository, history, FakeRankRepository())

        viewModel.onQueryChange("仙")
        viewModel.onQueryChange("仙剑")
        dispatcher.scheduler.advanceTimeBy(DEBOUNCE_MS / 2)
        dispatcher.scheduler.runCurrent()

        assertEquals(emptyList<String>(), history.added)
        assertEquals(emptyList<String>(), repository.queries)
    }

    @Test
    fun `one valid search records history once`() = runTest(dispatcher.scheduler) {
        val repository = FakeSearchRepository()
        val viewModel = SearchViewModel(repository, history, FakeRankRepository())

        viewModel.onQueryChange("仙剑")
        // 一次输入只发一次请求：后面的按键都把上一个去抖任务取消了
        viewModel.onQueryChange("仙剑奇")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("仙剑奇"), history.added)
        assertEquals(listOf("仙剑奇"), repository.queries)
        assertEquals(listOf("仙剑奇"), viewModel.state.value.history)
    }

    @Test
    fun `history click searches immediately and moves to front`() = runTest(dispatcher.scheduler) {
        val store = FakeSearchHistoryStore(initial = listOf("旧词", "其它"))
        val repository = FakeSearchRepository()
        val viewModel = SearchViewModel(repository, store, FakeRankRepository())
        dispatcher.scheduler.runCurrent()

        viewModel.onHistoryClick("旧词")
        // 不推进时间也搜了：说明打断去抖，走的是立即搜索那条路
        dispatcher.scheduler.runCurrent()

        assertEquals("旧词", viewModel.state.value.query)
        assertEquals(listOf("旧词"), repository.queries)
        assertEquals(listOf("旧词", "其它"), viewModel.state.value.history)
    }

    @Test
    fun `recommendation failure stays silent`() = runTest(dispatcher.scheduler) {
        val failure = AppResult.Failure(AppError.Network(null))
        val viewModel = SearchViewModel(FakeSearchRepository(failure), history, FakeRankRepository(failure))

        viewModel.onQueryChange("仙剑")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        // 搜索自己的错误照旧：推荐失败既不写 error，也不清推荐/搜索数据
        assertNotNull(state.error)
        assertEquals(emptyList<Vod>(), state.recommendations)
        assertFalse(state.recommendationsLoading)
        assertTrue(state.searched)
    }

    private companion object {
        /** 与 `SearchViewModel` 的 `DEBOUNCE_MS` 一致。 */
        const val DEBOUNCE_MS = 300L
    }
}

/** 记录每次 `add`，并像真实实现那样去重、把新词提到最前。 */
private class FakeSearchHistoryStore(initial: List<String> = emptyList()) : SearchHistoryStore {

    val added: MutableList<String> = mutableListOf()

    private var current: List<String> = initial

    override fun cached(): List<String> = current

    override suspend fun add(keyword: String): List<String> {
        added += keyword
        current = listOf(keyword) + current.filterNot { it == keyword }
        return current
    }

    override suspend fun clear() {
        current = emptyList()
    }
}

/** 只实现搜索页用到的方法；[search] 记下真正下发的关键词。 */
private class FakeSearchRepository(
    private val result: AppResult<List<Vod>> = AppResult.Success(emptyList()),
) : VodRepository {

    val queries: MutableList<String> = mutableListOf()

    override suspend fun list(type: Int, page: Int, limit: Int) = result

    override suspend fun detail(vodId: Int): AppResult<Vod> = AppResult.Failure(AppError.Unknown(null))

    override suspend fun search(keyword: String, limit: Int, typeId: Int?): AppResult<List<Vod>> {
        queries += keyword
        return result
    }
}

/** 推荐位只读 [board]；[catalog] 是接口要求，给空目录。 */
private class FakeRankRepository(
    private val result: AppResult<List<Vod>> = AppResult.Success(emptyList()),
) : RankRepository {

    override suspend fun board(orderBy: String, type: Int, page: Int, limit: Int): AppResult<List<Vod>> = result

    override suspend fun catalog(): AppResult<RankCatalog> =
        AppResult.Success(RankCatalog(promoted = emptyList(), groups = emptyList()))
}
