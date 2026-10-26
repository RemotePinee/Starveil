package com.starveil.feature.home

import androidx.compose.runtime.Immutable
import com.starveil.core.model.HomeBanner
import com.starveil.core.model.HomeLink
import com.starveil.core.model.LiveChannel
import com.starveil.core.model.Topic
import com.starveil.core.model.Vod

/**
 * 首页的对外动作。
 *
 * feature 不持有 NavController：页面只上报"用户点了什么"，具体跳哪一页由 `:app` 决定
 * （AGENTS.md 第 8 节）。这样首页可以独立预览、独立测试。
 */
@Immutable
data class HomeActions(
    val onMenu: () -> Unit = {},
    val onSearch: () -> Unit = {},
    val onFilter: () -> Unit = {},
    val onShortcut: (HomeShortcut) -> Unit = {},
    val onVodClick: (Vod) -> Unit = {},
    val onRetry: () -> Unit = {},
    /** 下拉刷新。与 [onRetry] 分开：错误态的重试会先落回 Loading 骨架，下拉刷新只更新内容。 */
    val onRefresh: () -> Unit = {},
    val onSelectType: (Int) -> Unit = {},
    val onBack: () -> Unit = {},
    val onBannerClick: (HomeBanner) -> Unit = {},
    val onLinkClick: (HomeLink) -> Unit = {},
    /** 专题卡片点击；跳哪一页由 `:app` 决定，feature 不认识导航。 */
    val onTopicClick: (Topic) -> Unit = {},
    /** 直播卡片点击；同上。 */
    val onLiveClick: (LiveChannel) -> Unit = {},
)

/** 首页快捷入口，对应截图里搜索栏下方那一排胶囊。 */
enum class HomeShortcut {
    ALL,
    RANK,
    TOPIC,
    LIVE,
    MEMBER,
    USER,
}
