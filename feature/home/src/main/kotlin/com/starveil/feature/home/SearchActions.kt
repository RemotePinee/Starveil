package com.starveil.feature.home

import com.starveil.core.model.Vod

/** 搜索页的对外动作。参数超过 5 个会被 detekt 拒，所以收成一个对象。 */
data class SearchActions(
    val onQueryChange: (String) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onVodClick: (Vod) -> Unit = {},
    val onBack: () -> Unit = {},
)
