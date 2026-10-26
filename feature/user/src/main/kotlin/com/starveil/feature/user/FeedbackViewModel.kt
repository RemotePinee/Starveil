package com.starveil.feature.user

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.core.common.AppResult
import com.starveil.core.data.FeedbackRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 反馈提交（`POST v1.gbook`）。
 *
 * 服务端限制：登录必需、单条不超过 300 字（超出会被截断）、同一用户 60 秒一条。
 * 成功后清空输入并提示，不自动退出页面——用户可能想接着写第二条。
 */
class FeedbackViewModel(private val repository: FeedbackRepository) : ViewModel() {

    private val _state = MutableStateFlow(FeedbackUiState())
    val state: StateFlow<FeedbackUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun onContentChange(value: String) {
        _state.update { it.copy(content = value.take(MAX_LENGTH)) }
    }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true) }
            when (val result = repository.submit(current.content.trim())) {
                is AppResult.Success -> {
                    _state.update { it.copy(isSubmitting = false, content = "") }
                    _messages.trySend(SENT)
                }

                is AppResult.Failure -> {
                    _state.update { it.copy(isSubmitting = false) }
                    _messages.trySend(result.error.toPointsMessage())
                }
            }
        }
    }

    companion object {
        fun factory(repository: FeedbackRepository) = viewModelFactory {
            initializer { FeedbackViewModel(repository) }
        }
    }
}

/** 服务端按 300 字截断，本地先卡住，避免用户写完被悄悄砍掉。 */
private const val MAX_LENGTH = 300

private const val SENT = "反馈已提交，谢谢"
