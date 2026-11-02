package com.starveil.feature.shortplay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.core.common.AppResult
import com.starveil.core.data.ShortPlayRepository
import com.starveil.core.model.ShortPlay
import com.starveil.core.model.ShortPlayType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 短剧分类页状态持有者。
 *
 * 两级分类：选一级后二级整组换掉，并且**默认选中「全部」**——保留上一级的选中项会查出
 * 一个空列表。换任一维都退回第 1 页并清空已加载结果。
 *
 * 分类树来自后台（macCMS `mac_type.type_pid`），客户端只展示；树为空时按仓库默认分类
 * 出一屏内容，页面不会停在空屏上。
 *
 * 首帧先铺仓库里该分类的上一次快照（AGENTS.md 第 9 节），网络回来原地覆盖。
 */
class ShortPlayCategoryViewModel(
    private val repository: ShortPlayRepository,
    /** 后台下发的分类树；每个一级节点的 [ShortPlayType.children] 是它的子分类。 */
    private val parents: List<ShortPlayType>,
    initialTypeId: Int,
    private val pageSize: Int = ShortPlayViewModel.PAGE_SIZE,
) : ViewModel() {

    private val initialSelection = resolveSelection(parents, initialTypeId)

    private val _state = MutableStateFlow(
        ShortPlayCategoryUiState(
            parents = parents,
            selectedParentId = initialSelection.parentId,
            children = childrenOf(initialSelection.parentId),
            selectedChildId = initialSelection.childId,
        ),
    )
    val state: StateFlow<ShortPlayCategoryUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        // 首帧先铺该档位的上一次快照（AGENTS.md 第 9 节），网络回来原地覆盖。
        seedFromSnapshot()
        loadFirstPage()
    }

    /**
     * 换一级分类：二级整组换成它的子分类，并默认选中「全部」。
     *
     * [ALL_CATEGORY_ID] 是虚拟的「全部」——二级行不收起，改为平铺所有父分类的子分类
     * （见 [ShortPlayCategoryUiState.visibleChildren]），所以 `children` 自身留空。
     */
    fun selectParent(parentId: Int) {
        val parent = parents.firstOrNull { it.id == parentId }
        if (parent == null && parentId != ALL_CATEGORY_ID) return
        if (parentId == _state.value.selectedParentId) return
        applySelection(
            parentId = parentId,
            childId = ALL_CATEGORY_ID,
            children = parent?.children.orEmpty(),
        )
    }

    /** 换二级分类；[childId] 为 [ALL_CATEGORY_ID] 表示「全部」（按父分类查）。 */
    fun selectChild(childId: Int) {
        val snapshot = _state.value
        if (childId != ALL_CATEGORY_ID && snapshot.visibleChildren.none { it.id == childId }) return
        if (childId == snapshot.selectedChildId) return
        applySelection(
            parentId = snapshot.selectedParentId,
            childId = childId,
            children = snapshot.children,
        )
    }

    fun loadMore() {
        val snapshot = _state.value
        // 首屏还没回来（page 仍是 0）时别翻页：翻的是同一页，只会把第一页请求两遍
        val busy = snapshot.isLoading || snapshot.isLoadingMore || snapshot.page < FIRST_PAGE
        if (busy || !snapshot.hasMore) return
        job = viewModelScope.launch { loadPage(page = snapshot.page + 1, reset = false) }
    }

    fun retry() = loadFirstPage()

    /** 「换一批」：重新拉当前分类第一页。 */
    fun refresh() = loadFirstPage()

    private fun applySelection(parentId: Int, childId: Int, children: List<ShortPlayType>) {
        // 换档先清空再铺快照：档位决定读哪一份（聚合档与单分类档各存各的）
        _state.update {
            it.copy(
                selectedParentId = parentId,
                selectedChildId = childId,
                children = children,
                items = emptyList(),
                page = 0,
                isLoading = true,
                isLoadingMore = false,
                hasMore = true,
                error = null,
            )
        }
        seedFromSnapshot()
        loadFirstPage()
    }

    private fun childrenOf(parentId: Int): List<ShortPlayType> =
        parents.firstOrNull { it.id == parentId }?.children.orEmpty()

    /** 首帧快照：聚合档读 `all` 那份，其余档位读单分类那份，两种档位互不覆盖。 */
    private fun seedFromSnapshot() {
        val snapshot = _state.value
        val cached = if (snapshot.queryTypeIds.isEmpty()) {
            repository.cachedShortPlay(snapshot.queryTypeId.takeIf { it > 0 })
        } else {
            repository.cachedShortPlayByTypes(snapshot.queryTypeIds)
        }
        if (!cached.isNullOrEmpty()) {
            _state.update { it.copy(items = cached, isLoading = false) }
        }
    }

    private fun loadFirstPage() {
        job?.cancel()
        _state.update { it.copy(isLoading = it.items.isEmpty(), isLoadingMore = false, error = null) }
        job = viewModelScope.launch { loadPage(page = FIRST_PAGE, reset = true) }
    }

    private suspend fun loadPage(page: Int, reset: Boolean) {
        val snapshot = _state.value
        // 聚合档（一级、二级都是「全部」）走 `type_ids`：所有父分类的并集。
        // 单分类档保持老口径：queryTypeId 为 0 时传 null，让仓库落回后台默认短剧分类——
        // 不能把 0 透给后端，后端 type=0 是不限分类，会变成全站影片混排。
        val typeIds = snapshot.queryTypeIds
        val result = if (typeIds.isEmpty()) {
            repository.feed(snapshot.queryTypeId.takeIf { it > 0 }, page, pageSize)
        } else {
            repository.feedByTypes(typeIds, page, pageSize)
        }
        when (result) {
            is AppResult.Success -> _state.update { it.onLoaded(result.value, reset) }
            is AppResult.Failure -> _state.update {
                it.copy(isLoading = false, isLoadingMore = false, error = result.error.toUserMessage())
            }
        }
    }

    private fun ShortPlayCategoryUiState.onLoaded(
        loaded: List<ShortPlay>,
        reset: Boolean,
    ): ShortPlayCategoryUiState = copy(
        // 首屏也去重：后端偶发重复条目时，LazyColumn 的 key 撞车会直接崩
        items = if (reset) {
            loaded.distinctBy { play -> play.id }
        } else {
            (items + loaded).distinctBy { play -> play.id }
        },
        page = if (reset) FIRST_PAGE else page + 1,
        // 不满一页就是最后一页：后端没给可靠的 total，只能按页大小判断
        hasMore = loaded.size >= pageSize,
        isLoading = false,
        isLoadingMore = false,
        error = null,
    )

    companion object {
        private const val FIRST_PAGE = 1

        fun factory(
            repository: ShortPlayRepository,
            parents: List<ShortPlayType>,
            initialTypeId: Int,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ShortPlayCategoryViewModel(repository, parents = parents, initialTypeId = initialTypeId)
            }
        }
    }
}

/** 进页落在哪一组：给的 id 是父分类就选它，是某个子分类就连父分类一起选中。 */
private data class CategorySelection(val parentId: Int, val childId: Int) {
    /** 实际请求的分类：选了子分类用它，否则用父分类（= 全部）。 */
    val queryTypeId: Int get() = childId.takeIf { it > 0 } ?: parentId
}

/**
 * 解析进页落点。
 *
 * 剧场页顶部横滑条可能停在任意一个分类上（父分类或子分类），所以两种都要认：
 * 先按父分类匹配，再在子分类里找它的父分类，都没有就退回第一个父分类。
 */
private fun resolveSelection(parents: List<ShortPlayType>, initialTypeId: Int): CategorySelection {
    val exactParent = parents.firstOrNull { it.id == initialTypeId }
    val parentOfChild = parents.firstOrNull { parent -> parent.children.any { it.id == initialTypeId } }
    return when {
        exactParent != null -> CategorySelection(exactParent.id, ALL_CATEGORY_ID)
        parentOfChild != null -> CategorySelection(parentOfChild.id, initialTypeId)
        else -> CategorySelection(parents.firstOrNull()?.id ?: ALL_CATEGORY_ID, ALL_CATEGORY_ID)
    }
}
