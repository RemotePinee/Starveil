package com.starveil.app.navigation

import android.content.Context
import androidx.navigation.NavHostController
import com.starveil.feature.home.HomeShortcut
import com.starveil.feature.live.LIVE_ROUTE
import com.starveil.feature.pay.payRoute
import com.starveil.feature.player.PlayerEvent
import com.starveil.feature.rank.RANK_ROUTE
import com.starveil.feature.topic.TOPIC_LIST_ROUTE
import com.starveil.feature.user.MEMBER_ENTRY_LABEL
import com.starveil.feature.user.UserNavigation

/**
 * 会员关闭时的入口拦截。
 *
 * 集中在这里而不是散在 NavHost builder：`AppDestinations` 已接近 400 行硬上限，
 * 且"哪些入口属于会员售卖"只应有一个判断，避免以后新增入口漏改。
 *
 * 拦截只针对**售卖/卡密**；历史会员的观看权限由服务端按到期时间放行，不在这里判断。
 */
internal fun NavHostController.openHomeShortcut(
    shortcut: HomeShortcut,
    memberEnabled: Boolean,
    context: Context,
) {
    when (shortcut) {
        HomeShortcut.RANK -> navigateSingleTop(RANK_ROUTE)
        HomeShortcut.TOPIC -> navigateSingleTop(TOPIC_LIST_ROUTE)
        HomeShortcut.LIVE -> navigateSingleTop(LIVE_ROUTE)
        HomeShortcut.MEMBER ->
            if (memberEnabled) navigateSingleTop(payRoute()) else context.showMemberClosed()

        HomeShortcut.USER -> navigateSingleTop(UserNavigation.USER_CENTER_ROUTE)
        HomeShortcut.ALL -> Unit
    }
}

/** 播放器事件：会员关闭时"开通会员"只提示，不跳已隐藏的会员页。 */
internal fun NavHostController.handlePlayerEvent(
    event: PlayerEvent,
    memberEnabled: Boolean,
    context: Context,
) {
    when {
        event is PlayerEvent.RequestUpgrade && memberEnabled -> navigate(payRoute())
        event is PlayerEvent.RequestUpgrade -> context.showMemberClosed()
        else -> context.showPlayerEvent(event)
    }
}

/** 用户中心入口：会员关闭时会员/卡密入口只提示。 */
internal fun NavHostController.handleUserEntry(
    label: String,
    memberEnabled: Boolean,
    context: Context,
) {
    if (!memberEnabled && isMembershipEntry(label)) {
        context.showMemberClosed()
    } else {
        navigateByEntryLabel(label)
    }
}

/** 会员卡按钮与卡密入口的文案；两者都落在会员页，关闭时必须一起拦。 */
private fun isMembershipEntry(label: String): Boolean =
    label == MEMBER_ENTRY_LABEL || label.contains("卡密")
