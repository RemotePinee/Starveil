package com.starveil.feature.user

import com.starveil.core.model.ContinueWatching

/**
 * 观看记录区的入参。
 *
 * 收成一个对象只为压住 Composable 的参数个数（detekt `LongParameterList` 阈值 5）；
 * 回调全在这里，页面只交代"点了哪张卡"。
 *
 * @param emptyHint 没有记录时那张卡上的文案（游客与已登录说法不同，见 `UserCenterUiState.historyEmptyHint`）
 * @param onEmptyClick 点空态卡：已登录去记录页，游客弹登录层
 */
internal data class WatchHistoryInput(
    val items: List<ContinueWatching>,
    val emptyHint: String,
    val onItemClick: (ContinueWatching) -> Unit,
    val onSeeAll: () -> Unit,
    val onEmptyClick: () -> Unit,
)
