package com.starveil.feature.shortplay

import com.starveil.core.model.ShortPlay
import com.starveil.core.model.ShortPlayType

/** 分类信息属于所有状态，首屏失败或空列表时顶部仍能显示分类、也能进分类页换一个。 */
sealed interface ShortPlayUiState {
    val types: List<ShortPlayType>
    /** 当前分类（顶部 tab 或全量分类的 id）；`0` 是「全部」的唯一表示，只有两边都没配才会出现。 */
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
        /**
         * [plays] 每真正换一次内容就 +1（首屏 / 追加 / 裁剪 / 搜索落页），
         * 进度、翻页、错误标记这些同在一个 state 里的字段都不动它。
         *
         * 信息流页拿它当 `remember` 键：拿整个 `plays` 当键时，每次重组都要对几百条
         * 短剧做一次逐字段深比较，滚动期间持续吃主线程。
         */
        val pagesVersion: Long = 0,
    ) : ShortPlayUiState

    data class Empty(
        val message: String = "暂无短剧",
        /** [message] 下面一行的补充：告诉用户下一步还能做什么。空串表示不显示。 */
        val hint: String = "",
        override val types: List<ShortPlayType> = emptyList(),
        override val selectedTypeId: Int = 0,
    ) : ShortPlayUiState

    data class Error(
        val message: String,
        override val types: List<ShortPlayType> = emptyList(),
        override val selectedTypeId: Int = 0,
    ) : ShortPlayUiState
}

/**
 * 整屏独占的文案态：空与失败。
 *
 * 它们自己铺满一整页（空态只有文案，失败态多一个重试按钮），占位层必须让开：占位层也在
 * 屏幕中间画分类名与细线，两层同时绘制就是「暂无短剧 / 分类名」叠在同一处。
 */
internal val ShortPlayUiState.isFullScreenNotice: Boolean
    get() = this is ShortPlayUiState.Empty || this is ShortPlayUiState.Error

/**
 * 这份状态是不是 [typeId] 这个分类的。
 *
 * 切分类时 ViewModel 原地保留上一屏内容（只打 `isRefreshing`），那份 `Content.selectedTypeId`
 * 仍是上一类——用这一处判断把"不属于本页的状态"挡在占位层之外。空态与播放浮层因此同源：
 * 不匹配的这一拍既不画文案、也不组合 feed（浮层只活在 feed 里），屏幕上只剩占位层。
 */
internal fun ShortPlayUiState.belongsTo(typeId: Int?): Boolean =
    typeId == null || selectedTypeId == typeId

/** 分页从 1 起，与后端 `v1.vod` 的 `page` 参数一致。 */
internal const val FIRST_PAGE = 1

/** 候选去抖：太短会在每个按键上打一次请求，太长打字时卡顿感明显。 */
internal const val SEARCH_DEBOUNCE_MS = 200L
