package com.starveil.feature.home

import com.starveil.core.model.Vod

/** 搜索页的对外动作。参数超过 5 个会被 detekt 拒，所以收成一个对象（全部带默认值，老调用点不必改）。 */
data class SearchActions(
    val onQueryChange: (String) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onVodClick: (Vod) -> Unit = {},
    val onBack: () -> Unit = {},
    /** 点历史词：填进输入框并立即搜索。 */
    val onHistoryClick: (String) -> Unit = {},
    val onClearHistory: () -> Unit = {},
    /** 点推荐卡片：与 [onVodClick] 同一条导航路径，ViewModel 不参与。 */
    val onRecommendationClick: (Vod) -> Unit = {},
)
