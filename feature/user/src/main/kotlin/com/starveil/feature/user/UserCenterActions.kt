package com.starveil.feature.user

import com.starveil.core.model.ContinueWatching

/** 「我的」页回调集合，避免 Composable 参数超过 detekt 阈值。 */
data class UserCenterActions(
    val onLoginClick: () -> Unit = {},
    val onEntryClick: (String) -> Unit = {},
    val onLogoutClick: () -> Unit = {},
    val onRetry: () -> Unit = {},
    /** 点观看记录卡片：与历史页同一套分流（短剧 → 竖屏播放器，普通影片 → 详情页）。 */
    val onHistoryClick: (ContinueWatching) -> Unit = {},
)
