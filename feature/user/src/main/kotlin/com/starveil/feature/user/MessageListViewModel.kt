package com.starveil.feature.user

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.core.common.AppResult
import com.starveil.core.data.MessageRepository
import com.starveil.core.model.AppMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 消息中心状态。不可变，一次性事件不走这里。 */
data class MessageListUiState(
    val items: List<AppMessage> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

/**
 * 消息列表。
 *
 * 打开时拉一次；点击条目时先本地标已读（UI 立刻响应），再发请求——
 * 接口失败不回滚：用户已经看过了，红点留到下次打开再纠正比"点了不变"更可接受。
 */
class MessageListViewModel(
    private val repository: MessageRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MessageListUiState())
    val state: StateFlow<MessageListUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = repository.list()) {
                is AppResult.Success -> _state.update {
                    it.copy(items = result.value.items, isLoading = false)
                }

                is AppResult.Failure -> _state.update {
                    it.copy(isLoading = false, errorMessage = ERROR_LOAD)
                }
            }
        }
    }

    fun markRead(message: AppMessage) {
        if (message.read) return
        _state.update { current ->
            current.copy(items = current.items.map { if (it.id == message.id) it.copy(read = true) else it })
        }
        viewModelScope.launch { repository.markRead(listOf(message.id)) }
    }

    companion object {
        fun factory(repository: MessageRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { MessageListViewModel(repository = repository) }
        }
    }
}

private const val ERROR_LOAD = "消息加载失败，请重试"
