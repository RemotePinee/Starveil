package com.starveil.feature.topic

import com.starveil.core.model.Topic
import com.starveil.core.model.TopicDetail

/**
 * 专题列表状态。不可变，通过 StateFlow 暴露（AGENTS.md 第 5 节）。
 *
 * 是一个 ListView 加下拉刷新，
 * 分页壳里的数据一次给全，所以没有"加载更多"。
 */
sealed interface TopicListUiState {

    data object Loading : TopicListUiState

    data class Content(
        val topics: List<Topic>,
        val isRefreshing: Boolean = false,
    ) : TopicListUiState

    data class Empty(val message: String = "暂无专题") : TopicListUiState

    data class Error(val message: String) : TopicListUiState
}

/**
 * 专题详情状态。详情内嵌影片数组，不分页（`TopicRepository.detail`）。
 *
 * [Empty] 仍然带着 [TopicDetail]：专题本身有效、只是没有影片时，
 * 头图与名称要照常显示，只把影片区换成一句提示。
 */
sealed interface TopicDetailUiState {

    data object Loading : TopicDetailUiState

    data class Content(val detail: TopicDetail) : TopicDetailUiState

    data class Empty(
        val detail: TopicDetail,
        val message: String = "该专题暂无影片",
    ) : TopicDetailUiState

    /** [retryable] 为 false 表示参数本身有问题，重试也不会有结果。 */
    data class Error(
        val message: String,
        val retryable: Boolean = true,
    ) : TopicDetailUiState
}

/**
 * 一次性事件。目前只有"已经有内容（快照或上次结果）、这次网络没成功"这一种，
 * 不进 UiState，避免 Snackbar 内容被重组重放（AGENTS.md 第 5 节）。
 */
data class TopicEvent(val text: String)
