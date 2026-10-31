package com.starveil.feature.shortplay

import androidx.compose.ui.unit.Dp
import com.starveil.core.designsystem.token.Spacing

/** 剧场页由 app 注册；播放器与跨页面跳转均由 app 注入，禁止 feature 互依赖。 */
const val SHORT_PLAY_ROUTE = "shortplay"

data class ShortPlayNavigation(
    val onSearch: () -> Unit = {},
    val onHistory: () -> Unit = {},
    val onFavorites: () -> Unit = {},
    val onFeedback: () -> Unit = {},
)

/**
 * 剧场页展示态：app 在 feed 与 series 之间原地切换时注入，只描述"看起来怎样"。
 * 默认值等同纯 feed，未接入的入口调用行为不变。
 */
data class ShortPlayPresentation(
    /** 全集面板已展开：菜单与抽屉一律关闭，避免叠出第二套导航。 */
    val seriesExpanded: Boolean = false,
    /** 手势锁（开合动画期间）：pager 不可滚，但已落页照常播放。 */
    val interactionLocked: Boolean = false,
    /** 0..1 展开进度：顶部栏据此上移淡出，不动播放器布局。 */
    val expansion: Float = 0f,
    /**
     * 底部 dock 的内容高度（不含导航栏）。
     *
     * 侧栏铺到全屏，但 dock 叠在页面之上并且不随侧栏消失，所以侧栏内容必须
     * 自己让出这一块，否则最后一项会被 dock 压住。0 表示没有 dock。
     */
    val dockInset: Dp = Spacing.none,
)
