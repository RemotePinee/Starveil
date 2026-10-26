package com.starveil.feature.rank

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.data.RankRepository
import com.starveil.core.model.RankCatalog
import com.starveil.core.model.Vod
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 榜单页状态持有者。只调仓库，不发 HTTP（AGENTS.md 第 4 节）。
 *
 * 加载顺序先 `vodPhbAll` 拿分类，
 * 再用第一个分类请求 `vodphb`。
 * 两个接口返回结构不同，分别落成 [RankCatalogState] 与 [RankBoardState]，不合并。
 *
 * 维度（[RankOrder]）放在 [RankUiState] 里：切换日/周/月只需用同一分类从第一页重取，
 * 不必重建 ViewModel。仓库只认 [RankOrder.value] 这两个接口约定的排序串，
 * **没有**"筛选""年份""地区"等其它维度，页面也不得自造。
 * [pageSize] 默认 20，与旧页面一致，单测可注入更小的值。
 */
class RankViewModel(
    private val repository: RankRepository,
    order: RankOrder = RankOrder.DAY,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) : ViewModel() {

    private val _state = MutableStateFlow(initialState(order))
    val state: StateFlow<RankUiState> = _state.asStateFlow()

    /**
     * 首帧状态：目录与首页列表都有快照时直接给出 Content，冷启动不再整屏转圈。
     * 缺任一份就退回原来的初始值，交给 [loadCatalog] 正常拉网络。
     */
    private fun initialState(order: RankOrder): RankUiState {
        val cachedCatalog = repository.cachedRankCatalog()
        val cachedBoard = repository.cachedRankBoard().orEmpty()
        val categories = cachedCatalog?.groups
            .orEmpty()
            .map { RankCategory(typeId = it.typeId, name = it.typeName) }
            .distinctBy { it.typeId }
        val first = categories.firstOrNull()
        val hasCatalog = cachedCatalog != null && first != null
        // 单个 return：detekt 的 ReturnCount 上限是 2；条件也拆成两段，别撞 ComplexCondition
        return if (hasCatalog && cachedBoard.isNotEmpty()) {
            RankUiState(
                catalog = RankCatalogState.Content(categories),
                selectedTypeId = first.typeId,
                order = order,
                board = RankBoardState.Content(
                    items = cachedBoard,
                    page = FIRST_PAGE,
                    endReached = cachedBoard.size < pageSize,
                ),
            )
        } else {
            RankUiState(order = order)
        }
    }

    private var catalogJob: Job? = null
    private var boardJob: Job? = null

    init {
        loadCatalog()
    }

    /**
     * 拉取分类目录；目录失败或为空时由 UI 调 [retry] 重走本方法。
     * 只有在还没有目录时才重置状态：已有目录（含快照铺的那份）时重载是为了刷新，
     * 不能把首帧内容打回转圈。
     */
    fun loadCatalog() {
        catalogJob?.cancel()
        boardJob?.cancel()
        // 已有目录时不重置；需要重置时只清目录与列表，保留用户选中的维度，
        // 避免重试后被悄悄改回日榜。
        if (_state.value.catalog !is RankCatalogState.Content) {
            _state.update { RankUiState(order = it.order) }
        }
        catalogJob = viewModelScope.launch {
            when (val result = repository.catalog()) {
                is AppResult.Success -> onCatalogLoaded(result.value)
                is AppResult.Failure -> applyCatalogFailure(result.error)
            }
        }
    }

    /**
     * 切换分类：保留当前列表，只打刷新标记再从第一页取（页码不继承）。
     *
     * 以前这里直接把 board 打回 Loading，等于让整页退回"转圈"；换分类是高频操作，
     * 每切一次闪一次全屏 loading，比数据慢本身更难受。
     */
    fun selectCategory(typeId: Int) {
        val current = _state.value
        val catalog = current.catalog
        val changed = catalog is RankCatalogState.Content && typeId != current.selectedTypeId
        if (changed) {
            _state.update { it.copy(selectedTypeId = typeId, board = it.board.refreshing()) }
            loadBoard(FIRST_PAGE, typeId)
        }
    }

    /**
     * 切换日/周/月维度：与 [selectCategory] 同口径，清空列表并从第一页重取。
     * 维度先落状态再发请求，保证顶栏高亮与列表数据始终是同一次查询的结果。
     */
    fun selectOrder(order: RankOrder) {
        val current = _state.value
        val typeId = current.selectedTypeId
        if (order == current.order) return
        if (typeId == null) {
            // 目录还没拿到：只改选中维度，等目录回来时按新维度取首页。
            _state.update { it.copy(order = order) }
            return
        }
        _state.update { it.copy(order = order, board = it.board.refreshing()) }
        loadBoard(FIRST_PAGE, typeId)
    }

    /** 触底加载下一页。已经在加载、已到底、或没有选中分类时直接忽略。 */
    fun loadMore() {
        val current = _state.value
        val typeId = current.selectedTypeId ?: return
        val board = current.board as? RankBoardState.Content ?: return
        if (!board.isAppending && !board.endReached) {
            loadBoard(board.page + 1, typeId)
        }
    }

    /** 失败重试：目录失败重拉目录，列表首页失败重拉首页，翻页失败重试下一页。 */
    fun retry() {
        val current = _state.value
        when (val catalog = current.catalog) {
            is RankCatalogState.Error, RankCatalogState.Empty -> loadCatalog()
            is RankCatalogState.Content -> retryBoard(catalog.categories)
            RankCatalogState.Loading -> Unit
        }
    }

    private fun retryBoard(categories: List<RankCategory>) {
        val current = _state.value
        val typeId = current.selectedTypeId
        val board = current.board
        if (typeId == null || categories.none { it.typeId == typeId }) {
            loadCatalog()
        } else if (board is RankBoardState.Error) {
            loadBoard(FIRST_PAGE, typeId)
        } else if (board is RankBoardState.Content && board.appendError != null) {
            loadBoard(board.page + 1, typeId)
        }
    }

    private fun onCatalogLoaded(catalog: RankCatalog) {
        // 只用 groups：promoted（后端 zhui）旧首页取出来没展示，语义与分类目录不同（Rank.kt）。
        val categories = catalog.groups
            .map { RankCategory(typeId = it.typeId, name = it.typeName) }
            .distinctBy { it.typeId }
        val first = categories.firstOrNull()
        if (first == null) {
            _state.update {
                it.copy(catalog = RankCatalogState.Empty, board = RankBoardState.Empty)
            }
        } else {
            _state.update {
                it.copy(catalog = RankCatalogState.Content(categories), selectedTypeId = first.typeId)
            }
            loadBoard(FIRST_PAGE, first.typeId)
        }
    }

    /** 目录已经有内容（快照或上一次成功）时保留内容，不把整页打成错误态。 */
    private fun applyCatalogFailure(error: AppError) {
        _state.update { state ->
            if (state.catalog is RankCatalogState.Content) {
                state
            } else {
                state.copy(catalog = RankCatalogState.Error(error.toMessage()))
            }
        }
    }

    private fun loadBoard(page: Int, typeId: Int) {
        boardJob?.cancel()
        markBoardLoading(page)
        boardJob = viewModelScope.launch {
            when (val result = repository.board(_state.value.order.value, typeId, page, pageSize)) {
                is AppResult.Success -> applyBoardPage(page, result.value)
                is AppResult.Failure -> applyBoardFailure(page, result.error)
            }
        }
    }

    private fun markBoardLoading(page: Int) {
        _state.update { state ->
            val board = state.board
            if (page == FIRST_PAGE) {
                // 已有列表（含快照铺的首帧）只打刷新标记，别把内容清成整屏转圈。
                state.copy(board = state.board.refreshing())
            } else if (board is RankBoardState.Content) {
                state.copy(board = board.copy(isAppending = true, appendError = null))
            } else {
                state
            }
        }
    }

    /**
     * 首页替换、翻页追加，统一按 id 去重（历史版本直接 `list.addAll`，
     * 见 ，后端重复时会出重复卡片）。
     */
    private fun applyBoardPage(page: Int, vods: List<Vod>) {
        _state.update { state ->
            val existing = (state.board as? RankBoardState.Content)?.items.orEmpty()
            val merged = if (page == FIRST_PAGE) vods else existing + vods
            val items = merged.distinctBy { it.id }
            val board = if (items.isEmpty()) {
                RankBoardState.Empty
            } else {
                RankBoardState.Content(
                    items = items,
                    page = page,
                    endReached = vods.size < pageSize,
                )
            }
            state.copy(board = board)
        }
    }

    /** 首页失败才整体报错；翻页失败保留已有列表，只在底部挂重试文案。 */
    private fun applyBoardFailure(page: Int, error: AppError) {
        val message = error.toMessage()
        _state.update { state ->
            val board = state.board
            val next = if (page == FIRST_PAGE || board !is RankBoardState.Content) {
                RankBoardState.Error(message)
            } else {
                board.copy(isAppending = false, appendError = message)
            }
            state.copy(board = next)
        }
    }

    companion object {
        private const val FIRST_PAGE = 1
        private const val DEFAULT_PAGE_SIZE = 20

        fun factory(
            repository: RankRepository,
            order: RankOrder = RankOrder.DAY,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { RankViewModel(repository = repository, order = order) }
        }
    }
}

/** 错误文案收敛在页面自己的文件里，避免跨模块共享字符串（与 HomeViewModel 同口径）。 */
private fun AppError.toMessage(): String = when (this) {
    is AppError.Network -> "网络连接失败，请检查网络后重试"
    is AppError.Http -> "服务异常（$code）"
    is AppError.Business -> message.ifBlank { "请求被拒绝" }
    is AppError.Serialization -> "数据解析失败"
    is AppError.Unknown -> "未知错误"
}
