package com.starveil.feature.user

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.data.ContinueWatchingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 观看记录页状态持有者。
 *
 * 数据源与首页「继续观看」同一份（`v1.user/videoProgressList`），不另建接口：
 * 服务端固定回最近 10 条，本页不翻页。
 *
 * 首帧先读 [ContinueWatchingRepository.cachedHistory]：进来直接看到上次的记录，
 * 网络回来原地覆盖；失败也不把已有内容打回错误页。
 */
class HistoryViewModel(private val repository: ContinueWatchingRepository) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        seedFromSnapshot()
        refresh()
    }

    /** 有快照就直接铺成「已加载」：进来先看到上次的记录，网络回来原地覆盖。 */
    private fun seedFromSnapshot() {
        val cached = repository.cachedHistory() ?: return
        _state.value = HistoryUiState(items = cached, isLoading = false)
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = it.items.isEmpty(), errorMessage = null) }
            when (val result = repository.recent()) {
                is AppResult.Success -> _state.update {
                    it.copy(isLoading = false, items = result.value)
                }

                is AppResult.Failure -> applyFailure(result.error)
            }
        }
    }

    /** 失败时已有记录（含首帧快照）就留着，只有空列表才整页报错。 */
    private fun applyFailure(error: AppError) {
        _state.update { current ->
            val message = error.toPointsMessage()
            if (current.items.isEmpty()) {
                current.copy(isLoading = false, errorMessage = message)
            } else {
                current.copy(isLoading = false)
            }
        }
    }

    companion object {
        fun factory(repository: ContinueWatchingRepository) = viewModelFactory {
            initializer { HistoryViewModel(repository) }
        }
    }
}
