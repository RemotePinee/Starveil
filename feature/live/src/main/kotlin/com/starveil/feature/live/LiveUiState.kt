package com.starveil.feature.live

import com.starveil.core.data.LiveGroup
import com.starveil.core.model.LiveChannel

/**
 * 直播列表状态。
 *
 * 把列表塞进适配器的可变集合，失败只在
 * `onError` 里 `printStackTrace`，用户看到的是永远的空列表。
 * 把四种结果显式化，刷新用 [Content.isRefreshing] 表达，不闪回整页 Loading。
 */
sealed interface LiveUiState {

    data object Loading : LiveUiState

    data class Content(
        val items: List<LiveChannel>,
        /** 全量条数（不是已加载条数），标题里的"共 N 路"用它。 */
        val total: Int = 0,
        /** 分类 + 每组数量，第一项 name 为空 = 全部。 */
        val groups: List<LiveGroup> = emptyList(),
        /** 当前选中的分类，空串 = 全部。 */
        val activeGroup: String = "",
        val isRefreshing: Boolean = false,
        /** 底部正在拉下一页：列表尾部显示一行加载提示，不闪回整页 Loading。 */
        val isLoadingMore: Boolean = false,
        /** 还有下一页。为 false 时列表底部显示"已经到底了"。 */
        val hasMore: Boolean = false,
    ) : LiveUiState

    data class Empty(val message: String) : LiveUiState

    data class Error(val message: String) : LiveUiState
}

/**
 * 单路直播流的播放状态。
 *
 * 历史版本点开频道直接交给 X5 网页播放，播放成败由 WebView 决定，
 * 应用侧拿不到状态；现在由 [LivePlayerViewModel] 持有，播放器只上报事实。
 */
sealed interface LivePlaybackState {

    data object Idle : LivePlaybackState

    data object Buffering : LivePlaybackState

    data object Playing : LivePlaybackState

    data class Error(val message: String) : LivePlaybackState
}

/**
 * 播放区状态。
 *
 * [attempt] 是重试序号：重试时播放地址不变，
 * [LivePlayer] 只能靠它判断"需要重新装载媒体源"。
 * [channel] 为空表示还没选台，播放区不渲染。
 */
data class LivePlayerUiState(
    val channel: LiveChannel? = null,
    val playback: LivePlaybackState = LivePlaybackState.Idle,
    val attempt: Int = 0,
)
