package com.starveil.feature.user

/** 「我的」页回调集合，避免 Composable 参数超过 detekt 阈值。 */
data class UserCenterActions(
    val onLoginClick: () -> Unit = {},
    val onEntryClick: (String) -> Unit = {},
    val onLogoutClick: () -> Unit = {},
    val onRetry: () -> Unit = {},
)
