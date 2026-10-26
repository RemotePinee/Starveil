package com.starveil.feature.detail

import com.starveil.core.model.PlaySource
import com.starveil.core.model.PlayUrl
import com.starveil.core.model.TrySeeGate
import com.starveil.core.model.Vod

/**
 * 详情页状态。
 *
 * 影片本体与播放源都来自同一个详情接口，所以 `Content` 直接持有 [Vod]；
 * 选中第几个源、第几集只存下标（0 起），与 [PlaySource.episodes] 的数组下标同口径，
 * 后端 sid / nid 不进状态——那两个值只在请求播放接口时用。
 *
 * @param sourceIndex 当前播放源下标，落到页面上的「播放源」标签
 * @param episodeIndex 当前集下标，选集网格据此高亮
 * @param isBlurbExpanded 简介是否展开，默认收起（旧页简介在独立的 `SummaryFragment` 弹层里）
 * @param isRefreshing 已有内容时重新拉详情，页面保留旧内容不闪白
 * @param isEpisodeGridExpanded 选集是否已「展开全部」；超过 [EPISODE_COLLAPSE_LIMIT] 集时先只摆前 30 个
 * @param trySee 试看闸门，null 表示未判定
 */
sealed interface DetailUiState {

    data object Loading : DetailUiState

    data class Content(
        val vod: Vod,
        val sourceIndex: Int = 0,
        val episodeIndex: Int = 0,
        val favorite: DetailFavoriteState = DetailFavoriteState.SignedOut,
        val isBlurbExpanded: Boolean = false,
        val isRefreshing: Boolean = false,
        /** 相关推荐。详情拉成功后再补；推荐失败保持空列表，不影响详情展示。 */
        val recommendations: List<Vod> = emptyList(),
        /** 推荐还在路上：区块内显示一行加载态，免得"空一块 → 突然出现"顶一下。 */
        val recommendationsLoading: Boolean = false,
        val isEpisodeGridExpanded: Boolean = false,
        /**
         * 试看闸门。详情页不自己请求试看接口：[com.starveil.core.data.AccessRepository.checkTrySee]
         * 是「源 + 集」维度的判定，也只在起播时调。
         * 留一个可空入口：拿到就显示提示条，拿不到（null）整条不渲染。
         */
        val trySee: TrySeeGate? = null,
    ) : DetailUiState {

        val source: PlaySource?
            get() = vod.playSources.getOrNull(sourceIndex)

        val selectedEpisode: PlayUrl?
            get() = source?.episodes?.getOrNull(episodeIndex)

        /** 当前源的集数，标题区的「共 N 集」用它。 */
        val episodeCount: Int
            get() = source?.episodes?.size ?: 0

        /** 收起态下被折叠掉的集数；0 表示不需要「展开全部」。 */
        val hiddenEpisodeCount: Int
            get() = (episodeCount - EPISODE_COLLAPSE_LIMIT).coerceAtLeast(0)

        /**
         * 选集网格真正要渲染的集。
         *
         * 收起态只取前 [EPISODE_COLLAPSE_LIMIT] 集：旧页把剧集铺成一条横滑列表
         * 长剧要横向拖很久；宫格 + 折叠
         * 让首屏高度可控，同时不丢"展开全部"这条路径。
         */
        val visibleEpisodes: List<PlayUrl>
            get() {
                val episodes = source?.episodes.orEmpty()
                return if (isEpisodeGridExpanded) {
                    episodes
                } else {
                    episodes.take(EPISODE_COLLAPSE_LIMIT)
                }
            }
    }

    /**
     * 详情拿到了但没有可播的源/集：旧页会显示空白的选集条
     */
    data class Empty(val message: String) : DetailUiState

    data class Error(val message: String) : DetailUiState
}

/**
 * 收藏按钮状态。
 *
 * 的"是否已收藏"是一个 `var isCollected`
 * 跨旋屏就丢；这里跟着 UI 状态走。
 * 未登录不叫"未收藏"，单独一档，页面据此提示登录而不是直接发请求。
 */
sealed interface DetailFavoriteState {

    /** 未登录：点收藏只提示登录，不发任何请求。 */
    data object SignedOut : DetailFavoriteState

    data object NotFavorited : DetailFavoriteState

    data object Favorited : DetailFavoriteState

    /** 请求在飞：按钮禁用，避免连点造成 add/remove 交错。 */
    data object Pending : DetailFavoriteState

    val isFavorited: Boolean
        get() = this is Favorited

    val isPending: Boolean
        get() = this is Pending
}

/**
 * 一次性事件（AGENTS.md 第 5 节：不放 `UiState`）。
 *
 * 收藏成功/失败、未登录提示都走 Toast，页面消费后不会因为重组重放。
 */
sealed interface DetailEvent {

    data class Toast(val message: String) : DetailEvent

    /** 评论发布成功：页面弹提示；输入框靠 [CommentSectionState.postedCount] 清空。 */
    data object CommentPosted : DetailEvent
}

/**
 * 收起态下先摆多少集。
 *
 * 6 列宫格的 5 行 = 30 个格子，正好压在首屏下方、不影响推荐与评论的曝光；
 * 超过这个数才出现「展开全部」（§18.3）。
 */
internal const val EPISODE_COLLAPSE_LIMIT = 30
