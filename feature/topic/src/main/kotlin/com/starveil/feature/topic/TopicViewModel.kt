package com.starveil.feature.topic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.core.common.AppResult
import com.starveil.core.data.TopicRepository
import com.starveil.core.model.Topic
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 专题列表状态持有者。只调仓库，不发 HTTP（AGENTS.md 第 3 节）。
 *
 * 刷新失败不清空已有列表：失败后什么都不做，
 * 保留旧数据并用一次性事件提示，体验上等价但不会白屏。
 *
 * 首帧优先用仓库快照（[TopicRepository.cachedTopics]）：有上次内容就直接是内容态，
 * 网络回来原地覆盖，进页面不再先整屏转圈。
 */
class TopicViewModel(
    private val repository: TopicRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<TopicListUiState>(cachedState())
    val state: StateFlow<TopicListUiState> = _state.asStateFlow()

    private val _events = Channel<TopicEvent>(Channel.BUFFERED)
    val events: Flow<TopicEvent> = _events.receiveAsFlow()

    init {
        load(isRefresh = false)
    }

    /** 下拉刷新：有内容时只打 refresh 标记，不闪加载态。 */
    fun refresh() = load(isRefresh = true)

    /** 错误态重试。 */
    fun retry() = load(isRefresh = false)

    /** 构造期同步读快照：读到内容就是首帧内容态，读不到才落到 Loading。 */
    private fun cachedState(): TopicListUiState =
        repository.cachedTopics()?.let { topics -> contentOf(topics) } ?: TopicListUiState.Loading

    private fun load(isRefresh: Boolean) {
        if (isRefresh) markRefreshing() else markLoading()
        viewModelScope.launch { apply(repository.list()) }
    }

    /** 首次加载与重试：已有内容时保持内容态，只让网络在背后补数据。 */
    private fun markLoading() {
        _state.update { current ->
            if (current is TopicListUiState.Content) current else TopicListUiState.Loading
        }
    }

    private fun markRefreshing() {
        _state.update { current ->
            if (current is TopicListUiState.Content) current.copy(isRefreshing = true) else current
        }
    }

    private suspend fun apply(result: AppResult<List<Topic>>) {
        when (result) {
            is AppResult.Success -> _state.value = contentOf(result.value)
            is AppResult.Failure -> onFailure(result.error.toUserMessage())
        }
    }

    /** 失败时只要屏幕上还有内容就不打回错误页，只把这次失败用一次性事件说出来。 */
    private suspend fun onFailure(message: String) {
        val current = _state.value
        if (current !is TopicListUiState.Content) {
            _state.value = TopicListUiState.Error(message)
            return
        }
        _state.value = current.copy(isRefreshing = false)
        _events.send(TopicEvent(message))
    }

    companion object {
        fun factory(repository: TopicRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { TopicViewModel(repository) }
        }
    }
}

private fun contentOf(topics: List<Topic>): TopicListUiState =
    if (topics.isEmpty()) TopicListUiState.Empty() else TopicListUiState.Content(topics)
