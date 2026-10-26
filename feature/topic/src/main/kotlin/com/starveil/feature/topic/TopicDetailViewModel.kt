package com.starveil.feature.topic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.core.common.AppResult
import com.starveil.core.data.TopicRepository
import com.starveil.core.model.TopicDetail
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 专题详情状态持有者。topicId 由导航参数传入，从 Intent 取
 * 再原样拼进详情接口（同文件 199 行）。
 *
 * 首帧优先用仓库快照（[TopicRepository.cachedTopicDetail]）：有上次详情就直接铺内容，
 * 网络回来原地覆盖，进页面不再先整屏转圈。
 */
class TopicDetailViewModel(
    private val repository: TopicRepository,
    private val topicId: String,
) : ViewModel() {

    private val _state = MutableStateFlow<TopicDetailUiState>(cachedState())
    val state: StateFlow<TopicDetailUiState> = _state.asStateFlow()

    private val _events = Channel<TopicEvent>(Channel.BUFFERED)
    val events: Flow<TopicEvent> = _events.receiveAsFlow()

    init {
        load()
    }

    fun retry() = load()

    /** 构造期同步读快照：id 为空或没存过都返回 null，调用方据此落回 Loading。 */
    private fun cachedState(): TopicDetailUiState =
        repository.cachedTopicDetail(topicId)?.let { detail -> contentOf(detail) } ?: TopicDetailUiState.Loading

    private fun load() {
        val id = topicId.trim()
        if (id.isEmpty()) {
            // 路由参数缺失/非法：不发请求，重试也没意义，直接给不可重试的错误态。
            _state.value = TopicDetailUiState.Error(message = "专题参数无效", retryable = false)
            return
        }
        markLoading()
        viewModelScope.launch { apply(repository.detail(id)) }
    }

    /** 只有从错误页重试才回到 Loading；已有内容（含快照首帧）时不打回。 */
    private fun markLoading() {
        _state.update { current ->
            if (current is TopicDetailUiState.Error) TopicDetailUiState.Loading else current
        }
    }

    private suspend fun apply(result: AppResult<TopicDetail>) {
        when (result) {
            is AppResult.Success -> _state.value = contentOf(result.value)
            is AppResult.Failure -> onFailure(result.error.toUserMessage())
        }
    }

    /** 失败时只要屏幕上还有内容就不打回错误页，只把这次失败用一次性事件说出来。 */
    private suspend fun onFailure(message: String) {
        when (_state.value) {
            is TopicDetailUiState.Content, is TopicDetailUiState.Empty ->
                _events.send(TopicEvent(message))
            else -> _state.value = TopicDetailUiState.Error(message)
        }
    }

    companion object {
        fun factory(
            repository: TopicRepository,
            topicId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { TopicDetailViewModel(repository = repository, topicId = topicId) }
        }
    }
}

private fun contentOf(detail: TopicDetail): TopicDetailUiState =
    if (detail.vods.isEmpty()) TopicDetailUiState.Empty(detail) else TopicDetailUiState.Content(detail)
