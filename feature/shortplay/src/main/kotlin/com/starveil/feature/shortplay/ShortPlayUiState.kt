package com.starveil.feature.shortplay

import com.starveil.core.model.ShortPlay
import com.starveil.core.model.ShortPlayType

/** 分类信息属于所有状态，首屏失败或空列表时仍可从抽屉切换分类。 */
sealed interface ShortPlayUiState {
    val types: List<ShortPlayType>
    val selectedTypeId: Int

    data class Loading(
        override val types: List<ShortPlayType> = emptyList(),
        override val selectedTypeId: Int = 0,
    ) : ShortPlayUiState

    data class Content(
        val plays: List<ShortPlay>,
        override val types: List<ShortPlayType> = emptyList(),
        override val selectedTypeId: Int = 0,
        val isRefreshing: Boolean = false,
        val isLoadingMore: Boolean = false,
        val endReached: Boolean = false,
        val page: Int = FIRST_PAGE,
        val appendError: String? = null,
        val refreshError: String? = null,
        /** 已稳定落页的短剧；由 ViewModel 保存，离开播放页返回不重置。 */
        val currentPlayId: Int? = plays.firstOrNull()?.id,
        /** 仅整份替换时变化，append 不要求 Pager 重新定位。 */
        val collectionVersion: Long = 0,
    ) : ShortPlayUiState

    data class Empty(
        val message: String = "暂无短剧",
        override val types: List<ShortPlayType> = emptyList(),
        override val selectedTypeId: Int = 0,
    ) : ShortPlayUiState

    data class Error(
        val message: String,
        override val types: List<ShortPlayType> = emptyList(),
        override val selectedTypeId: Int = 0,
    ) : ShortPlayUiState
}

/** 分页从 1 起，与后端 `v1.vod` 的 `page` 参数一致。 */
internal const val FIRST_PAGE = 1

/** 候选去抖：太短会在每个按键上打一次请求，太长打字时卡顿感明显。 */
internal const val SEARCH_DEBOUNCE_MS = 200L
