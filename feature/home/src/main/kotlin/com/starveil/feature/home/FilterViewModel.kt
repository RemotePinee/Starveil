package com.starveil.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.core.common.AppResult
import com.starveil.core.data.VodRepository
import com.starveil.core.model.Vod
import com.starveil.core.model.VodOrder
import com.starveil.core.model.VodType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 片库筛选页状态持有者。
 *
 * 换任一筛选条件都退回第 1 页并清空已加载结果：列表必须和当前条件严格对应，
 * 否则用户会看到"筛完还是上一批"。
 *
 * 分类列表只拉一次；拉失败只是少了几行筛选条，页面仍然按「全部」出结果，
 * 不会因为这条辅助数据整页报错。
 */
class FilterViewModel(
    private val repository: VodRepository,
    initialTypeId: Int,
) : ViewModel() {

    private val cached = repository.cachedFilter()

    private val _state = MutableStateFlow(
        // 上次的默认条件首屏直接铺出来：进筛选页先看到片库，再进去挑条件
        cached?.let { snap ->
            val selection = FilterSelection(typeId = initialTypeId.coerceAtLeast(0))
            FilterUiState(
                selection = selection,
                rows = buildFilterRows(snap.types, selection),
                items = snap.items,
                loadedPage = 1,
                isLoading = false,
                hasMore = snap.items.size >= VodRepository.DEFAULT_PAGE_SIZE,
            )
        } ?: FilterUiState(selection = FilterSelection(typeId = initialTypeId.coerceAtLeast(0))),
    )
    val state: StateFlow<FilterUiState> = _state.asStateFlow()

    // 分类优先用仓库里首页已经拉过的那份：拿到就能在首帧铺出筛选条，
    // 不用再排一次网络请求（那次要 147ms，正好落在进场动画里）。
    private var types: List<VodType> = repository.cachedTypes().ifEmpty { cached?.types.orEmpty() }
    private var job: Job? = null

    init {
        reload()
    }

    fun onSelect(kind: FilterKind, value: String) {
        val current = _state.value.selection
        val next = current.select(kind, value)
        if (next == current) return
        _state.update {
            it.copy(
                selection = next,
                rows = buildFilterRows(types, next),
                items = emptyList(),
                loadedPage = 0,
                hasMore = true,
            )
        }
        reload()
    }

    fun retry() = reload()

    /** 触底加载：首屏还没好、正在翻页、或者已经到底，都不发请求。 */
    fun loadMore() {
        val snapshot = _state.value
        // 拆成两步：三个条件并列会触发 detekt 的 ComplexCondition
        val busy = snapshot.isLoading || snapshot.isLoadingMore
        if (busy || !snapshot.hasMore) return
        job = viewModelScope.launch { loadPage(page = snapshot.loadedPage + 1, reset = false) }
    }

    private fun reload() {
        job?.cancel()
        job = viewModelScope.launch {
            if (types.isEmpty()) loadTypes()
            _state.update { it.copy(rows = buildFilterRows(types, it.selection)) }
            loadPage(page = FIRST_PAGE, reset = true)
        }
    }

    /**
     * 分类全量保留：一级分类渲染成第一行，子分类由 [buildFilterRows] 摆进第二行。
     * 过滤掉子分类的话，选中「电影」后就没有「动作片/喜剧片」可挑了。
     */
    private suspend fun loadTypes() {
        val result = repository.types()
        types = (result as? AppResult.Success)?.value.orEmpty()
    }

    private suspend fun loadPage(page: Int, reset: Boolean) {
        // 已有快照首屏时不要整屏转圈：后台刷新，列表原地留着
        _state.update { it.copy(isLoading = reset && it.items.isEmpty(), isLoadingMore = !reset, error = null) }
        val selection = _state.value.selection
        val result = repository.filter(
            type = selection.typeId,
            page = page,
            limit = PAGE_SIZE,
            area = selection.area,
            year = selection.year,
            lang = selection.lang,
            className = selection.className,
            order = VodOrder.sanitize(selection.order),
        )
        when (result) {
            is AppResult.Success -> onPageLoaded(page = page, loaded = result.value, reset = reset)
            is AppResult.Failure -> _state.update {
                it.copy(isLoading = false, isLoadingMore = false, error = result.error.toMessage())
            }
        }
    }

    private fun onPageLoaded(page: Int, loaded: List<Vod>, reset: Boolean) {
        _state.update { current ->
            current.copy(
                items = if (reset) loaded else current.items + loaded,
                loadedPage = page,
                // 不满一页就是最后一页：后端没给可靠的 total，只能按页大小判断
                hasMore = loaded.size >= PAGE_SIZE,
                isLoading = false,
                isLoadingMore = false,
                error = null,
            )
        }
    }

    companion object {
        /** 与仓库默认分页一致：三列刚好铺满两屏多。 */
        private const val PAGE_SIZE = VodRepository.DEFAULT_PAGE_SIZE

        private const val FIRST_PAGE = 1

        fun factory(
            repository: VodRepository,
            initialTypeId: Int = 0,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                FilterViewModel(repository = repository, initialTypeId = initialTypeId)
            }
        }
    }
}
