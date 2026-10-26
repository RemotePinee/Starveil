package com.starveil.feature.shortplay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.core.common.AppError
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

/** 随机短剧信息流。快照先显示，失败不清空；代际校验隔离取消后的迟到结果。 */
class ShortPlayViewModel(
    private val repository: ShortPlayRepository,
    private val pageSize: Int = PAGE_SIZE,
    private val types: List<ShortPlayType> = emptyList(),
    private val shuffle: (List<ShortPlay>) -> List<ShortPlay> = { it.shuffled() },
) : ViewModel() {
    private var selectedTypeId: Int = types.firstOrNull()?.id ?: 0
    private val _state = MutableStateFlow<ShortPlayUiState>(ShortPlayUiState.Loading(types, selectedTypeId))
    val state: StateFlow<ShortPlayUiState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var generation = 0L
    private var collectionVersion = 0L
    private var pinnedPlaybackId: Int? = null

    init {
        seedFromSnapshot()
        loadFirstPage(resetPosition = false)
    }

    private fun seedFromSnapshot() {
        val cached = repository.cachedShortPlay(selectedTypeId.takeIf { it > 0 }).orEmpty()
        if (cached.isNotEmpty()) applyFirstPage(cached, resetPosition = true)
    }

    fun refresh() = loadFirstPage(resetPosition = true)

    /**
     * 全屏播放期间钉住当前列表：取消在途首屏并作废代际，保留已有内容与顺序，
     * 追加仍按原顺序接在尾部；解 pin 不主动刷新，等用户 refresh 或离开该项。
     */
    fun pinPlayback(playId: Int?) {
        if (playId == null) {
            pinnedPlaybackId = null
            return
        }
        pinnedPlaybackId = playId
        loadJob?.cancel()
        generation++
        _state.update { current ->
            val content = current as? ShortPlayUiState.Content ?: return@update current
            val anchor = playId.takeIf { id -> content.plays.any { it.id == id } } ?: content.currentPlayId
            content.copy(currentPlayId = anchor, isRefreshing = false, isLoadingMore = false)
        }
    }

    fun onPageSettled(playId: Int) {
        if (pinnedPlaybackId?.let { it != playId } == true) return
        _state.update { current ->
            val content = current as? ShortPlayUiState.Content ?: return@update current
            if (content.plays.any { it.id == playId }) content.copy(currentPlayId = playId) else content
        }
    }

    fun retry() {
        val content = _state.value as? ShortPlayUiState.Content
        if (content?.appendError != null) loadMore() else loadFirstPage(resetPosition = false)
    }

    fun loadMore() {
        val content = _state.value as? ShortPlayUiState.Content ?: return
        val busy = content.isLoadingMore || content.isRefreshing
        if (busy || content.endReached) return
        val nextPage = content.page + 1
        val requestType = selectedTypeId
        val requestGeneration = generation
        _state.value = content.copy(isLoadingMore = true, appendError = null)
        loadJob = viewModelScope.launch {
            val result = repository.feed(requestType.takeIf { it > 0 }, nextPage, pageSize)
            if (!isCurrentRequest(requestType, requestGeneration)) return@launch
            when (result) {
                is AppResult.Success -> applyNextPage(nextPage, result.value)
                is AppResult.Failure -> applyAppendFailure(result.error.toUserMessage())
            }
        }
    }

    fun selectType(typeId: Int) {
        if (pinnedPlaybackId != null) return
        if (typeId == selectedTypeId || types.none { it.id == typeId }) return
        selectedTypeId = typeId
        // 不要先打回整屏 Loading：那会让切分类先闪一屏黑底转圈（外层底色是 scrimStrong）。
        // 保留当前内容，等新分类的快照或网络结果回来原地替换——与首页切分类同口径。
        seedFromSnapshot()
        loadFirstPage(resetPosition = false)
    }

    private fun loadFirstPage(resetPosition: Boolean) {
        if (pinnedPlaybackId != null) return
        val requestGeneration = ++generation
        val requestType = selectedTypeId
        loadJob?.cancel()
        markRefreshing()
        loadJob = viewModelScope.launch {
            val result = repository.feed(requestType.takeIf { it > 0 }, FIRST_PAGE, pageSize)
            if (!isCurrentRequest(requestType, requestGeneration)) return@launch
            when (result) {
                is AppResult.Success -> applyFirstPage(result.value, resetPosition)
                is AppResult.Failure -> applyFirstPageFailure(result.error.toUserMessage())
            }
        }
    }

    private fun isCurrentRequest(typeId: Int, requestGeneration: Long): Boolean =
        typeId == selectedTypeId && requestGeneration == generation

    private fun markRefreshing() {
        _state.update { current ->
            if (current is ShortPlayUiState.Content) {
                current.copy(isRefreshing = true, isLoadingMore = false, appendError = null, refreshError = null)
            } else {
                ShortPlayUiState.Loading(types, selectedTypeId)
            }
        }
    }

    private fun applyFirstPageFailure(message: String) {
        _state.update { current ->
            if (current is ShortPlayUiState.Content) {
                current.copy(isRefreshing = false, refreshError = message)
            } else {
                ShortPlayUiState.Error(message, types, selectedTypeId)
            }
        }
    }

    private fun applyFirstPage(plays: List<ShortPlay>, resetPosition: Boolean) {
        if (pinnedPlaybackId != null) return
        val previousId = (_state.value as? ShortPlayUiState.Content)?.currentPlayId
        val shuffled = shuffle(plays).distinctBy { it.id }
        val retainedId = previousId?.takeIf { id -> !resetPosition && shuffled.any { it.id == id } }
        collectionVersion++
        _state.value = if (shuffled.isEmpty()) {
            ShortPlayUiState.Empty(types = types, selectedTypeId = selectedTypeId)
        } else {
            ShortPlayUiState.Content(
                plays = shuffled,
                types = types,
                selectedTypeId = selectedTypeId,
                endReached = plays.size < pageSize,
                currentPlayId = retainedId ?: shuffled.first().id,
                collectionVersion = collectionVersion,
            )
        }
    }

    private fun applyNextPage(page: Int, plays: List<ShortPlay>) {
        _state.update { current ->
            val content = current as? ShortPlayUiState.Content ?: return@update current
            content.copy(
                plays = (content.plays + shuffle(plays)).distinctBy { it.id },
                page = page,
                isLoadingMore = false,
                endReached = plays.size < pageSize,
                appendError = null,
            )
        }
    }

    private fun applyAppendFailure(message: String) {
        _state.update { current ->
            val content = current as? ShortPlayUiState.Content ?: return@update current
            content.copy(isLoadingMore = false, appendError = message)
        }
    }

    companion object {
        const val PAGE_SIZE = 20

        fun factory(
            repository: ShortPlayRepository,
            types: List<ShortPlayType> = emptyList(),
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ShortPlayViewModel(repository = repository, types = types) }
        }
    }
}

private fun AppError.toUserMessage(): String = when (this) {
    is AppError.Network -> "网络异常，点此重试"
    is AppError.Http -> "服务异常（$code）"
    is AppError.Business -> message.ifBlank { "请求被拒绝" }
    is AppError.Serialization -> "数据解析失败"
    is AppError.Unknown -> "未知错误"
}
