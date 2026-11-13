package com.starveil.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.starveil.core.designsystem.LocalDockInset
import com.starveil.core.designsystem.token.Spacing
import com.starveil.feature.player.PLAYER_ROUTE
import com.starveil.feature.player.VERTICAL_PLAY_ROUTE
import com.starveil.feature.shortplay.SHORT_PLAY_ROUTE

/** dock 的高度：底部内容高 + 系统导航栏。tab 页按它让位。 */
internal val LocalTabDockHeight = staticCompositionLocalOf { Spacing.none }

/** 后台配的底栏 tab 路由集合：决定一个 destination 要不要给 dock 让位。 */
internal val LocalTabRoutes = staticCompositionLocalOf<Set<String>> { emptySet() }

/**
 * tab 页的让位框：**让位跟着页面走**，不挂在 NavHost 外面。
 *
 * 这一页是 tab 就自己让出 dock 的高度，页面背景停在 dock 上方，下面那层 dock 露出来；
 * 播放页 / 二级页不让位、铺满整屏，直接把 dock 盖住。转场时各图各算，旧页继续露着 dock，
 * 不存在"点击那一帧 dock 先被盖住"。
 */
@Composable
internal fun TabDockFrame(route: String?, content: @Composable () -> Unit) {
    val dockInset = LocalTabDockHeight.current
    val routes = LocalTabRoutes.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = dockFramePadding(route, routes, dockInset)),
    ) {
        // 让位已经在外面做掉了，页面里的列表不用再自己留一条；
        // 没让位的 tab（短剧）仍要靠它留出最后一项不被 dock 压住。
        CompositionLocalProvider(
            LocalDockInset provides pageDockInset(route, routes, dockInset),
        ) {
            content()
        }
    }
}

/**
 * 这一页要不要自己让出 dock 的高度。纯函数，测试直接打它。
 *
 * 只有 tab 页让位，而且短剧页与沉浸播放页除外——它们的视频要铺到 dock 后面。
 */
internal fun leavesBottomToDock(route: String?, tabRoutes: Set<String>): Boolean =
    isTabRoute(route, tabRoutes) && !isVideoBehindDock(route)

/** 这一页是不是底栏 tab。 */
private fun isTabRoute(route: String?, tabRoutes: Set<String>): Boolean =
    route != null && route in tabRoutes

/** 视频要铺到 dock 后面的页面：短剧页与沉浸播放页，它们不让位。 */
private fun isVideoBehindDock(route: String?): Boolean =
    route == SHORT_PLAY_ROUTE || isImmersiveRoute(route)

/** 页面根要加的底部让位。 */
internal fun dockFramePadding(route: String?, tabRoutes: Set<String>, dockInset: Dp): Dp =
    if (leavesBottomToDock(route, tabRoutes)) dockInset else Spacing.none

/** 交给页面内部的 dock 占位：已让位的页不用再留，没让位的短剧要留。 */
internal fun pageDockInset(route: String?, tabRoutes: Set<String>, dockInset: Dp): Dp {
    if (!isTabRoute(route, tabRoutes)) return Spacing.none
    return if (leavesBottomToDock(route, tabRoutes)) Spacing.none else dockInset
}

/**
 * 转场期间 dock 画在页面之上还是之下。
 *
 * 短剧页的视频要铺到 dock 后面，所以 dock 必须压在上面；普通 tab 页的页面根已经让出
 * 了高度，dock 压在它上面与压在下面视觉等价。**两端必须一起判**：只看 `currentRoute`
 * 的话，从短剧切回普通页的第一帧 dock 就掉到页面下方，退场中的短剧视频会把 dock 盖住
 * 整整一档动画、结尾再"跳"出来——首页与短剧两个方向的观感不同就出在这里。
 *
 * 另一端是二级页时不压上去：那一页铺满整屏，本来就该盖住 dock。
 */
internal fun dockOnTopFor(currentRoute: String?, settledRoute: String?, tabRoutes: Set<String>): Boolean {
    val shortPlaySide = currentRoute == SHORT_PLAY_ROUTE || settledRoute == SHORT_PLAY_ROUTE
    if (!shortPlaySide) return false
    val other = if (currentRoute == SHORT_PLAY_ROUTE) settledRoute else currentRoute
    return other == SHORT_PLAY_ROUTE || (other != null && other in tabRoutes)
}

/**
 * dock 与页面底边的关系。
 *
 * dock 与页面是同一层里的前后两层：dock 先画，页面后画。所以"页面能看见 dock"
 * 这件事只有一条成立条件——**这一页自己的底边让位了**。让位必须属于页面自己，
 * 不能是 NavHost 外面那一层共享的 padding：那一层在点击的第一帧就按新路由算，
 * 旧页立刻铺满整屏，把 dock 盖住，看着就是"dock 先没了"。
 */

/** [route] 是不是整页沉浸的播放页（横屏 / 竖屏播放）。 */
internal fun isImmersiveRoute(route: String?): Boolean =
    route?.startsWith("$VERTICAL_PLAY_ROUTE/") == true || route?.startsWith("$PLAYER_ROUTE/") == true

/** dock 占的高度：内容高 + 系统导航栏那一条。 */
internal fun dockHeight(showTabIcons: Boolean, systemBottom: Dp): Dp =
    (if (showTabIcons) Spacing.tabBarHeightIcons else Spacing.tabBarHeightText) + systemBottom

/**
 * NavHost 这一层只扣系统安全区。
 *
 * dock 的让位不在这里：它属于每个 tab 页面自己（见 [TabDockFrame]）。
 * 放在这一层的话，点击那一帧就按新路由重算，滑出的旧页立刻铺满、盖住 dock。
 * 反过来，有 dock 的页面这里必须给 0：页面要铺到屏幕底，dock 才从它底边露出来；
 * 这里留一条导航栏高度的话，页面下边界正好压在 dock 上面，dock 就一点都不剩了。
 */
internal fun contentBottomInset(currentRoute: String?, hasDock: Boolean, systemBottom: Dp): Dp = when {
    // 整页沉浸的播放页与短剧页自己铺到屏幕底，安全区也由页面处理
    isImmersiveRoute(currentRoute) -> Spacing.none
    currentRoute == SHORT_PLAY_ROUTE -> Spacing.none
    hasDock -> Spacing.none
    else -> systemBottom
}
