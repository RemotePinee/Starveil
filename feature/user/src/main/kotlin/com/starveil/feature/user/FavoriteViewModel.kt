package com.starveil.feature.user

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.core.common.AppResult
import com.starveil.core.data.AuthRepository
import com.starveil.core.data.FavoriteRepository
import com.starveil.core.model.FavoriteItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 收藏页状态持有者。
 *
 * 四处与不同，都是为了修旧行为：
 * 1. 旧页第 1 页既覆盖又追加，刷新会出重复条目；
 * 按 [FavoriteUiState.loadedPage] 判覆盖还是追加，追加时再按 ulogId 去重。
 * 2. 旧页取消收藏成功后把页码重置回 1 整页重拉（`:203-206`），删一条丢一次滚动位置；
 * 成功后只从内存列表移除该条。
 * 3. 旧页删除失败 `onError` 是空的（`:212-214`），用户看不到任何反馈；
 * 把失败文案放进状态，交给 UI 弹 Toast。
 * 4. 有首帧快照时先按 [FavoriteRepository.cachedFavorites] 铺成「已加载」，
 * 进来不再整屏转圈；网络回来原地覆盖，失败也不把快照内容打回错误页。
 */
class FavoriteViewModel(
    private val favoriteRepository: FavoriteRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FavoriteUiState())
    val state: StateFlow<FavoriteUiState> = _state.asStateFlow()

    init {
        seedFromSnapshot()
        loadFirstPage(isRefresh = false)
    }

    /** 有快照就直接铺成「已加载」：进来先看到上次的收藏，网络回来原地覆盖。 */
    private fun seedFromSnapshot() {
        val items = favoriteRepository.cachedFavorites() ?: return
        _state.value = FavoriteUiState(
            items = items,
            loadedPage = FIRST_PAGE,
            isInitialLoading = false,
            hasMore = items.size >= PAGE_SIZE,
        )
    }

    /** 首屏重试：错误态里的"重试"按钮。 */
    fun retry() = loadFirstPage(isRefresh = false)

    /** 下拉刷新：保留旧列表直到新页回来，避免整页闪白。 */
    fun refresh() = loadFirstPage(isRefresh = true)

    /** 触底加载下一页。 */
    fun loadMore() {
        val current = _state.value
        if (!current.canLoadMore) return
        _state.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            val next = current.loadedPage + 1
            when (val result = favoriteRepository.list(next)) {
                is AppResult.Success -> applyPage(page = next, pageItems = result.value, replace = false)
                is AppResult.Failure -> _state.update {
                    it.copy(isLoadingMore = false, message = result.error.toPageMessage())
                }
            }
        }
    }

    /** 取消收藏：成功后从当前列表就地移除。 */
    fun remove(item: FavoriteItem) {
        if (item.vodId in _state.value.removingIds) return
        _state.update { it.copy(removingIds = it.removingIds + item.vodId) }
        viewModelScope.launch {
            when (val result = favoriteRepository.remove(listOf(item.vodId))) {
                is AppResult.Success -> applyRemoved(item)
                is AppResult.Failure -> _state.update {
                    it.copy(
                        removingIds = it.removingIds - item.vodId,
                        message = result.error.toPageMessage(),
                    )
                }
            }
        }
    }

    /** Toast 已弹出，清空一次性提示，避免旋屏重放。 */
    fun consumeMessage() = _state.update { it.copy(message = null) }

    /**
     * 首屏加载。已有内容（含首帧快照）时只收错误、不打回整屏加载态；
     * [isRefresh] 区分「用户下拉」与「进页面时补一次」——后者不弹刷新指示器。
     */
    private fun loadFirstPage(isRefresh: Boolean) {
        val hasContent = _state.value.items.isNotEmpty()
        _state.update {
            it.copy(
                isInitialLoading = !hasContent,
                isRefreshing = isRefresh && hasContent,
                errorMessage = null,
            )
        }
        viewModelScope.launch {
            if (authRepository.restore() == null) {
                applySignedOut()
                return@launch
            }
            when (val result = favoriteRepository.list(FIRST_PAGE)) {
                is AppResult.Success -> applyPage(FIRST_PAGE, result.value, replace = true)
                is AppResult.Failure -> applyFailure(result.error.toPageMessage())
            }
        }
    }

    private fun applyPage(page: Int, pageItems: List<FavoriteItem>, replace: Boolean) {
        _state.update { current ->
            current.copy(
                items = if (replace) pageItems else current.items.appended(pageItems),
                loadedPage = page,
                isInitialLoading = false,
                isRefreshing = false,
                isLoadingMore = false,
                hasMore = pageItems.size >= PAGE_SIZE,
                errorMessage = null,
                needsLogin = false,
            )
        }
    }

    private fun applyRemoved(item: FavoriteItem) {
        _state.update { current ->
            current.copy(
                items = current.items.filterNot { it.ulogId == item.ulogId },
                removingIds = current.removingIds - item.vodId,
                message = "已取消收藏",
            )
        }
    }

    /** 首屏失败给整页错误态（可重试）；已有列表时刷新失败只弹提示，不把列表清空。 */
    private fun applyFailure(errorMessage: String) {
        _state.update { current ->
            val failure = current.copy(
                isInitialLoading = false,
                isRefreshing = false,
                isLoadingMore = false,
            )
            if (current.items.isEmpty()) {
                failure.copy(errorMessage = errorMessage)
            } else {
                failure.copy(message = errorMessage)
            }
        }
    }

    private fun applySignedOut() {
        _state.update {
            it.copy(
                isInitialLoading = false,
                isRefreshing = false,
                isLoadingMore = false,
                needsLogin = true,
                errorMessage = null,
            )
        }
    }

    companion object {
        fun factory(
            favoriteRepository: FavoriteRepository,
            authRepository: AuthRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { FavoriteViewModel(favoriteRepository, authRepository) }
        }
    }
}

/** 追加下一页：用 `addData`，跨页重复条目会被插两次。 */
private fun List<FavoriteItem>.appended(next: List<FavoriteItem>): List<FavoriteItem> {
    val known = mapTo(HashSet()) { it.ulogId }
    return this + next.filterNot { it.ulogId in known }
}

/** 与 `:core:data` 的 `FavoriteRepository` 页长一致（旧  每页 10 条）。 */
private const val PAGE_SIZE = 10

private const val FIRST_PAGE = 1
