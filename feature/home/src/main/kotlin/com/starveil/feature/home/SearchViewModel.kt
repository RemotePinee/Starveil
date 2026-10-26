package com.starveil.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.starveil.core.common.AppResult
import com.starveil.core.data.VodRepository
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
 * 输入防抖 300ms，避免每敲一个字都打一次接口。只靠"点搜索才发"，
 * 但那样重输一次就要再点一次）。空关键词直接清空结果，不发请求。
 */
class SearchViewModel(private val repository: VodRepository) : ViewModel() {

    data class State(
        val query: String = "",
        val items: List<Vod> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val searched: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
        job?.cancel()
        if (value.isBlank()) {
            _state.update { it.copy(items = emptyList(), searched = false, error = null, isLoading = false) }
            return
        }
        job = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            load(value)
        }
    }

    fun retry() {
        val keyword = _state.value.query.trim()
        if (keyword.isEmpty()) return
        job?.cancel()
        job = viewModelScope.launch { load(keyword) }
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

        fun factory(repository: VodRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SearchViewModel(repository) as T
            }
    }
}
