package com.starveil.feature.live

import androidx.compose.runtime.Immutable
import com.starveil.core.model.LiveChannel

/**
 * 页面回调集合。
 *
 * 集中成不可变对象，既避免 Composable 参数超限（AGENTS.md 第 2 节），
 * 也让列表与播放区共用一个动作入口，不把 ViewModel 传进纯 UI 组件。
 */
@Immutable
class LiveActions(
    val onRefresh: () -> Unit = {},
    val onLoadMore: () -> Unit = {},
    /** 切换分类，空串 = 全部。 */
    val onSelectGroup: (String) -> Unit = {},
    val onSelectChannel: (LiveChannel) -> Unit = {},
    val onRetryPlayer: () -> Unit = {},
    val onPlaybackStarted: () -> Unit = {},
    val onPlaybackBuffering: () -> Unit = {},
    val onPlaybackError: (String) -> Unit = {},
)
