package com.starveil.app.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.Broadcast
import com.adamglin.phosphoricons.fill.ChartBar
import com.adamglin.phosphoricons.fill.Crown
import com.adamglin.phosphoricons.fill.Hash
import com.adamglin.phosphoricons.fill.House
import com.adamglin.phosphoricons.fill.SquaresFour
import com.adamglin.phosphoricons.fill.Star
import com.adamglin.phosphoricons.fill.User
import com.starveil.core.model.BottomTab
import com.starveil.core.model.SecondTab
import com.starveil.core.model.StartupConfig
import com.starveil.feature.live.LIVE_ROUTE
import com.starveil.feature.pay.PAY_ROUTE_PATTERN
import com.starveil.feature.pay.payRoute
import com.starveil.feature.rank.RANK_ROUTE
import com.starveil.feature.shortplay.SHORT_PLAY_ROUTE
import com.starveil.feature.topic.TOPIC_LIST_ROUTE
import com.starveil.feature.user.UserNavigation

private const val HOME_TAB = "home"
private const val SECOND_TAB = "second"
private const val RANK_TAB = "rank"
private const val MEMBER_TAB = "member"
private const val MINE_TAB = "mine"

/** 底栏同时显示的上限。后台"显示"勾选也卡在 5，这里是脏数据兜底。 */
private const val MAX_BOTTOM_TABS = 5

/**
 * 底栏一项：点击目标 + 选中判定 + 展示文案 + 图标名。
 *
 * [route] 与 [selectedRoute] 分开：会员页注册的是带占位符的 [PAY_ROUTE_PATTERN]，
 * 点击地址由 [payRoute] 拼出 `pay?payMode=...`，文本不同，只有模式串能和 `currentRoute` 比较。
 */
internal data class BottomTabSpec(
    val id: String,
    val route: String,
    val selectedRoute: String,
    val label: String,
    val icon: String,
)

/**
 * `bottom_tabs` 为空或启动配置加载失败时用默认五栏（保持旧行为）。
 * 只决定"显示什么"，隐藏项对应的路由仍由 `AppDestinations` 全量注册。
 */
internal fun bottomTabSpecs(config: StartupConfig?): List<BottomTabSpec> {
    if (config == null || config.bottomTabs.isEmpty()) {
        // 老后端没有 member_enabled 时按开启处理；明确关闭时才过滤默认五栏里的会员
        return defaultBottomTabSpecs(config?.secondTab ?: SecondTab.TOPIC)
            .filter { config?.memberEnabled != false || it.id != MEMBER_TAB }
    }
    val memberEnabled = config.memberEnabled
    val visible = config.bottomTabs
        // 服务端已在 startup 里过滤会员；这里再兜一次，防旧缓存/后端把入口带回来。
        // 按 id 和 mode 双判：新增的自定义按钮也可能指向会员中心（mode = vip）。
        .filter { it.visible && (memberEnabled || (it.id != MEMBER_TAB && it.mode != "vip")) }
        .sortedBy { it.order }
        .mapNotNull { it.toSpec() }
        // 底栏最多 5 个：上限本来由后台勾选控制，这里兜一层"脏数据也不撑破底栏"
        .take(MAX_BOTTOM_TABS)
    // 服务端把所有项都关掉时至少留下首页与我的，否则用户没有任何导航入口。
    return visible.ifEmpty {
        defaultBottomTabSpecs(config.secondTab).filter { it.id == HOME_TAB || it.id == MINE_TAB }
    }
}

private fun defaultBottomTabSpecs(secondTab: SecondTab): List<BottomTabSpec> = listOf(
    BottomTabSpec(HOME_TAB, HOME_ROUTE, HOME_ROUTE, "首页", "home"),
    BottomTabSpec(
        id = SECOND_TAB,
        route = secondTabRoute(secondTab),
        selectedRoute = secondTabRoute(secondTab),
        label = if (secondTab == SecondTab.TOPIC) "专题" else "直播",
        icon = if (secondTab == SecondTab.TOPIC) "topic" else "live",
    ),
    BottomTabSpec(RANK_TAB, RANK_ROUTE, RANK_ROUTE, "榜单", "rank"),
    BottomTabSpec(MEMBER_TAB, payRoute(), PAY_ROUTE_PATTERN, "会员", "vip"),
    BottomTabSpec(MINE_TAB, UserNavigation.USER_CENTER_ROUTE, UserNavigation.USER_CENTER_ROUTE, "我的", "user"),
)

private fun BottomTab.toSpec(): BottomTabSpec? {
    val route = routeOf(this) ?: return null
    return BottomTabSpec(
        id = id,
        route = route,
        selectedRoute = if (id == MEMBER_TAB) PAY_ROUTE_PATTERN else route,
        label = label.ifBlank { defaultLabel(id, mode) },
        icon = icon.ifBlank { id },
    )
}

/**
 * 这一栏点进去是哪个路由。
 *
 * **由 `mode` 决定，不再由 id 写死**：后台那一列「点进去看什么」五栏都能改，
 * 想把「榜单」改成直播就改成直播。id 只用来认"这是哪个栏位的默认值"，
 * 未知 id 仍返回 null（服务端加了新栏位时客户端跳过，而不是崩在 `when` 上）。
 */
private fun routeOf(tab: BottomTab): String? {
    // id 只用来认"默认值"，跳哪一页由 mode 决定：后台可以新增任意按钮，
    // 早期这里对未知 id 直接返回 null，会把新增的按钮整栏丢掉。
    if (tab.id.isBlank()) return null
    val mode = tab.mode.ifBlank { defaultModeOf(tab.id) }
    return routeForMode(mode)
}

/** mode → 路由。会员与个人中心走各自带参数的地址，其余是一对一。 */
private fun routeForMode(mode: String): String = when (mode) {
    "home" -> HOME_ROUTE
    "live" -> LIVE_ROUTE
    "shortplay" -> SHORT_PLAY_ROUTE
    "rank" -> RANK_ROUTE
    "vip" -> payRoute()
    "user" -> UserNavigation.USER_CENTER_ROUTE
    // topic 及未知 mode 一律按专题处理
    else -> TOPIC_LIST_ROUTE
}

/** 没配 `mode` 时的默认跳转目标，与后台 `HomePage::defaultModeFor()` 保持一致。 */
private fun defaultModeOf(id: String): String = when (id) {
    HOME_TAB -> "home"
    RANK_TAB -> "rank"
    MEMBER_TAB -> "vip"
    MINE_TAB -> "user"
    else -> "topic"
}

private fun secondTabRoute(secondTab: SecondTab): String =
    if (secondTab == SecondTab.TOPIC) TOPIC_LIST_ROUTE else LIVE_ROUTE

private fun defaultLabel(id: String, mode: String): String = when (id) {
    HOME_TAB -> "首页"
    SECOND_TAB -> when (mode) {
        "live" -> "直播"
        "shortplay" -> "短剧"
        else -> "专题"
    }
    RANK_TAB -> "榜单"
    MEMBER_TAB -> "会员"
    MINE_TAB -> "我的"
    else -> id
}

/** `bottom_tabs.icon` → Phosphor 图标；未知图标退到 SquaresFour，不抛异常。 */
internal fun bottomTabIcon(name: String): ImageVector = when (name.trim().lowercase()) {
    "home" -> PhosphorIcons.Fill.House
    "rank" -> PhosphorIcons.Fill.ChartBar
    "vip", "member" -> PhosphorIcons.Fill.Crown
    "user", "mine" -> PhosphorIcons.Fill.User
    "topic" -> PhosphorIcons.Fill.Hash
    "shortplay" -> PhosphorIcons.Fill.Star
    "live" -> PhosphorIcons.Fill.Broadcast
    else -> PhosphorIcons.Fill.SquaresFour
}
