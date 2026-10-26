package com.starveil.feature.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.core.model.LiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 选台与播放状态。
 *
 * 历史版本点卡片是"调起 X5 网页"，没有选台概念。现在：
 * [select] 只负责换台并把状态推到 [LivePlaybackState.Buffering]，
 * 真正的装载由 [LivePlayer] 监听 `(url, attempt)` 完成；
 * 播放器回调经 [onBuffering] / [onPlaying] / [onError] 回写状态。
 *
 * 不持有 Context、不碰 ExoPlayer，所以这个类在 JVM 测试里可直接构造。
 */
class LivePlayerViewModel : ViewModel() {

    private val _state = MutableStateFlow(LivePlayerUiState())
    val state: StateFlow<LivePlayerUiState> = _state.asStateFlow()

    /** 选台。地址为空时不进缓冲态，直接报错，避免播放器拿空 URI 去 prepare。 */
    fun select(channel: LiveChannel) {
        val current = _state.value
        _state.value = if (channel.url.isNullOrBlank()) {
            current.copy(channel = channel, playback = LivePlaybackState.Error(NO_URL_MESSAGE))
        } else {
            current.copy(
                channel = channel,
                playback = LivePlaybackState.Buffering,
                attempt = current.attempt + 1,
            )
        }
    }

    /** 重试当前频道：attempt 递增，播放区据此重新装载。未选台时不做任何事。 */
    fun retry() {
        val channel = _state.value.channel ?: return
        select(channel)
    }

    fun onBuffering() = updatePlayback { LivePlaybackState.Buffering }

    fun onPlaying() = updatePlayback { LivePlaybackState.Playing }

    /** 播放器上报的是已经翻好的整句（见 [liveErrorMessage]），这里只兜住空串。 */
    fun onError(message: String) = updatePlayback {
        LivePlaybackState.Error(message.ifBlank { PLAYBACK_FAILED })
    }

    private fun updatePlayback(next: () -> LivePlaybackState) {
        _state.update { current ->
            if (current.channel == null) current else current.copy(playback = next())
        }
    }

    companion object {
        private const val NO_URL_MESSAGE = "该频道没有可播放地址"
        private const val PLAYBACK_FAILED = "播放失败，换个台试试"

        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer { LivePlayerViewModel() }
        }
    }
}
