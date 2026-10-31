package com.starveil.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.starveil.core.common.AppResult
import com.starveil.core.data.RankRepository
import com.starveil.core.data.SearchHistoryStore
import com.starveil.core.data.VodRepository
import com.starveil.core.model.RankCatalog
import com.starveil.core.model.Vod
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 搜索页状态。
 *
 * 输入防抖 300ms，避免每敲一个字都打一次接口。空关键词直接清空结果，不发请求。
 *
 * 历史只在"真的发出了一次搜索"时写（[submit]）：输入过程中的每个按键只改 query，
 * 不碰历史。推荐位是锦上添花——进页面拉一次热播榜切片，失败静默，
 * 不占用搜索自己的错误态。
 */
class SearchViewModel(
    private val repository: VodRepository,
    private val historyStore: SearchHistoryStore = EmptySearchHistoryStore,
    private val rankRepository: RankRepository = EmptyRankRepository,
) : ViewModel() {

    data class State(
        val query: String = "",
        val items: List<Vod> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val searched: Boolean = false,
        val history: List<String> = emptyList(),
        val recommendations: List<Vod> = emptyList(),
        val recommendationsLoading: Boolean = false,
    )

    // 内存副本同步铺上，首帧不转圈；读盘由 store 自己后台预热，见 [refreshHistory]。
    private val _state = MutableStateFlow(State(history = historyStore.cached()))
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    init {
        refreshHistory()
        loadRecommendations()
    }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
        job?.cancel()
        if (value.isBlank()) {
            _state.update { it.copy(items = emptyList(), searched = false, error = null, isLoading = false) }
            return
        }
        job = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            submit(value)
        }
    }

    /** 点历史词：打断去抖，立刻走同一条「写历史 + 搜索」的路径。 */
    fun onHistoryClick(keyword: String) {
        val word = keyword.trim()
        if (word.isEmpty()) return
        job?.cancel()
        _state.update { it.copy(query = word) }
        job = viewModelScope.launch { submit(word) }
    }

    fun onClearHistory() {
        viewModelScope.launch {
            historyStore.clear()
            _state.update { it.copy(history = emptyList()) }
        }
    }

    fun retry() {
        val keyword = _state.value.query.trim()
        if (keyword.isEmpty()) return
        job?.cancel()
        job = viewModelScope.launch { load(keyword) }
    }

    /**
     * 唯一的历史写入点。
     *
     * 只有去抖到点（[onQueryChange]）或点历史词（[onHistoryClick]）才走到这里，
     * 所以连打几个字不会写历史；`add` 自带去重与「提到最前」。
     */
    private suspend fun submit(keyword: String) {
        if (keyword.isBlank()) return
        val history = historyStore.add(keyword)
        _state.update { it.copy(history = history) }
        load(keyword)
    }

    /** 构造时内存副本可能还没预热完：后台再取一次，只补空，不覆盖已显示的内容。 */
    private fun refreshHistory() {
        viewModelScope.launch {
            val fromDisk = historyStore.cached()
            if (_state.value.history.isEmpty() && fromDisk.isNotEmpty()) {
                _state.update { it.copy(history = fromDisk) }
            }
        }
    }

    /**
     * 推荐位 = 热播总榜第一页的前 10 条（`type` 1 是榜单首个分类，typeId 从 1 起）。
     *
     * 失败静默：不写 [State.error]、不清空已有推荐——搜索页的错误态只属于搜索本身。
     */
    private fun loadRecommendations() {
        viewModelScope.launch {
            _state.update { it.copy(recommendationsLoading = true) }
            val request = rankRepository.board(RECOMMEND_ORDER, RECOMMEND_TYPE, FIRST_PAGE, RECOMMEND_LIMIT)
            if (request is AppResult.Success) {
                _state.update {
                    it.copy(
                        recommendations = request.value.take(RECOMMEND_LIMIT),
                        recommendationsLoading = false,
                    )
                }
            } else {
                _state.update { it.copy(recommendationsLoading = false) }
            }
        }
    }

    private suspend fun load(keyword: String) {
        _state.update { it.copy(isLoading = true, error = null) }
        when (val result = repository.search(keyword)) {
            is AppResult.Success -> _state.update {
                it.copy(items = result.value, isLoading = false, searched = true)
            }

            is AppResult.Failure -> _state.update {
                it.copy(isLoading = false, searched = true, error = result.error.toMessage())
            }
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 300L

        /** 推荐位：热播总榜第一页，取前 10 条。 */
        private const val RECOMMEND_ORDER = "vod_hits desc"
        private const val RECOMMEND_TYPE = 1
        private const val RECOMMEND_LIMIT = 10
        private const val FIRST_PAGE = 1

        fun factory(
            repository: VodRepository,
            historyStore: SearchHistoryStore = EmptySearchHistoryStore,
            rankRepository: RankRepository = EmptyRankRepository,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SearchViewModel(repository, historyStore, rankRepository) as T
            }
    }
}

/** 未注入历史存储时的空实现：读不到也不写，其它入口照旧构造本类。 */
private object EmptySearchHistoryStore : SearchHistoryStore

/** 未注入榜单仓库时的空实现：推荐位保持空，不发请求。 */
private object EmptyRankRepository : RankRepository {
    override suspend fun board(orderBy: String, type: Int, page: Int, limit: Int): AppResult<List<Vod>> =
        AppResult.Success(emptyList())

    override suspend fun catalog(): AppResult<RankCatalog> =
        AppResult.Success(RankCatalog(promoted = emptyList(), groups = emptyList()))
}
