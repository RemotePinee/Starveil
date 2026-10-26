package com.starveil.feature.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.data.LiveRepository
import com.starveil.core.model.LiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 直播列表状态持有者，只管列表（选台与播放状态在 [LivePlayerViewModel]）。
 *
 * 分页由后端 `v1.zhibo` 的 `page`/`limit` 支持：后台导几百个频道时，
 * 早期客户端一次只拿 20 条，列表看起来永远只有 20 个。这里按页累积，
 * 滚到底部自动拉下一页，刷新时回到第一页。
 */
class LiveViewModel(private val repository: LiveRepository) : ViewModel() {

    private val _state = MutableStateFlow<LiveUiState>(LiveUiState.Loading)
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    /** 已经成功加载到第几页。 */
    private var loadedPage = 0
    private var loadingMore = false

    /** 当前分类，空串 = 全部。换分类等于换一份列表，页码从 1 重来。 */
    private var activeGroup = ""

    init {
        seedFromSnapshot()
        load(refreshing = false)
    }

    /** 有快照就直接铺成「已加载」：进来不再整屏转圈，网络回来原地覆盖。 */
    private fun seedFromSnapshot() {
        val page = repository.cachedLivePages()?.firstOrNull() ?: return
        if (page.items.isEmpty()) return
        // 快照存的就是第一页，翻页从这里接着往后取
        loadedPage = 1
        _state.value = LiveUiState.Content(
            items = page.items,
            total = page.total,
            groups = page.groups,
            hasMore = page.hasMore,
        )
    }

    fun refresh() = load(refreshing = true)

    /** 切换分类。点同一个分类不做任何事，避免重复请求。 */
    fun selectGroup(group: String) {
        if (group == activeGroup) {
            return
        }
        activeGroup = group
        load(refreshing = false)
    }

    /** 滑到底部时调用。重复调用会被 [loadingMore]、刷新中与 [LiveUiState.Content.hasMore] 挡掉。 */
    fun loadMore() {
        val current = _state.value as? LiveUiState.Content ?: return
        // 条件拆成两段：detekt 的 ComplexCondition 阈值是 3，三个 `||` 会踩线
        val busy = loadingMore || !current.hasMore
        if (busy || current.isRefreshing) {
            return
        }
        loadingMore = true
        _state.value = current.copy(isLoadingMore = true)
        viewModelScope.launch { fetchNext(current.items) }
    }

    private fun load(refreshing: Boolean) {
        loadingMore = false
        _state.update { current ->
            when {
                // 已有内容（含首帧快照）就不打回 Loading，只挂刷新态，避免整页闪白
                current is LiveUiState.Content ->
                    current.copy(isRefreshing = refreshing, isLoadingMore = false)
                refreshing -> current
                else -> LiveUiState.Loading
            }
        }
        viewModelScope.launch { fetchFirst() }
    }

    private suspend fun fetchFirst() {
        when (val result = repository.list(page = 1, group = activeGroup)) {
            is AppResult.Success -> {
                loadedPage = 1
                val page = result.value
                _state.value = if (page.items.isEmpty()) {
                    LiveUiState.Empty(EMPTY_MESSAGE)
                } else {
                    LiveUiState.Content(
                        items = page.items,
                        total = page.total,
                        groups = page.groups,
                        activeGroup = activeGroup,
                        hasMore = page.hasMore,
                    )
                }
            }

            is AppResult.Failure -> onFirstPageFailure(result.error)
        }
    }

    /**
     * 首屏失败：有内容就留着内容，只收掉刷新态——网络抖一次不该把已经看到的频道清空。
     * 没有内容才进 Error，给用户重试入口。
     */
    private fun onFirstPageFailure(error: AppError) {
        val current = _state.value
        if (current is LiveUiState.Content) {
            // 屏上留着上一组频道：分类跟着退回去，别让翻页按下一次点击的分类去取下一页
            activeGroup = current.activeGroup
            _state.value = current.copy(isRefreshing = false)
            return
        }
        _state.value = LiveUiState.Error(error.toLiveMessage())
    }

    private suspend fun fetchNext(existing: List<LiveChannel>) {
        val next = loadedPage + 1
        val current = _state.value as? LiveUiState.Content
        when (val result = repository.list(page = next, group = activeGroup)) {
            is AppResult.Success -> {
                loadedPage = next
                loadingMore = false
                _state.value = LiveUiState.Content(
                    // 去重：翻页期间后台可能新增/删除条目，导致某条跨页重复出现
                    items = (existing + result.value.items).distinctBy { it.id },
                    total = result.value.total,
                    groups = result.value.groups,
                    activeGroup = activeGroup,
                    hasMore = result.value.hasMore,
                )
            }

            is AppResult.Failure -> {
                // 失败不改页码也不清列表：保留已加载内容，用户可以再滑一次重试
                loadingMore = false
                _state.value = LiveUiState.Content(
                    items = existing,
                    total = current?.total ?: existing.size,
                    groups = current?.groups.orEmpty(),
                    activeGroup = activeGroup,
                    hasMore = true,
                )
            }
        }
    }

    companion object {
        private const val EMPTY_MESSAGE = "暂无直播"

        fun factory(repository: LiveRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { LiveViewModel(repository) }
        }
    }
}

/**
 * 错误文案收敛在本模块一处，避免列表与播放区各写一套。
 */
internal fun AppError.toLiveMessage(): String = when (this) {
    is AppError.Network -> "网络连接失败，请检查网络后重试"
    is AppError.Http -> "服务异常（$code）"
    is AppError.Business -> message.ifBlank { "请求被拒绝" }
    is AppError.Serialization -> "数据解析失败"
    is AppError.Unknown -> "未知错误"
}
