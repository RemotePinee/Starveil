package com.starveil.app.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.navigation.NavHostController
import android.widget.Toast
import com.starveil.core.model.HomeBanner
import com.starveil.core.model.HomeLink
import com.starveil.feature.detail.detailRoute
import com.starveil.feature.pay.payRoute
import com.starveil.feature.live.LIVE_ROUTE
import com.starveil.feature.topic.TOPIC_LIST_ROUTE
import com.starveil.feature.user.UserNavigation

/**
 * 首页运营位（轮播 / 快捷入口）的点击路由。
 *
 * 后台配置里既有站内 id 也有外链：能落到站内页面的走导航，其余 http(s) 交给系统浏览器，
 * 其它 scheme 一律丢弃——配置来自服务端，不能让它起任意 Activity。
 */
internal fun NavHostController.openHomeBanner(banner: HomeBanner, context: Context) {
    if (banner.vodId > 0) {
        navigate(detailRoute(banner.vodId))
    } else {
        context.openExternalUrl(banner.url)
    }
}

internal fun NavHostController.openHomeLink(link: HomeLink, context: Context) {
    when (link.targetType.lowercase()) {
        "vod" -> if (link.targetId > 0) navigate(detailRoute(link.targetId))
        "topic", "second" -> navigateSingleTop(TOPIC_LIST_ROUTE)
        "live" -> navigateSingleTop(LIVE_ROUTE)
        else -> context.openExternalUrl(link.url)
    }
}

/** 只放行 http(s)，避免后台填 `intent://`、`file://` 之类把客户端当跳板。 */
private fun Context.openExternalUrl(url: String) {
    val isHttp = url.startsWith("http://", ignoreCase = true) ||
        url.startsWith("https://", ignoreCase = true)
    if (!isHttp) {
        return
    }
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/** 「我的」页功能项点按：按文案落到现有目的地，没有对应页面的保持无跳转。 */
internal fun NavHostController.navigateByEntryLabel(label: String) {
    when {
        label.contains("会员") || label.contains("续费") -> navigateSingleTop(payRoute())
        label.contains("收藏") -> navigate(UserNavigation.FAVORITE_ROUTE)
        // 我的页「积分」格与积分中心共用这条：签到/任务都在这一页
        label.contains("积分") -> navigate(UserNavigation.POINTS_ROUTE)
        label.contains("观看记录") || label.contains("历史") -> navigate(UserNavigation.HISTORY_ROUTE)
        label.contains("反馈") -> navigate(UserNavigation.FEEDBACK_ROUTE)
        label.contains("提现") -> navigate(UserNavigation.WITHDRAW_ROUTE)
        // 顶栏设置图标与功能列表"设置"共用一条：旧 AccountSettingActivity → PROFILE_ROUTE
        label.contains("设置") -> navigate(UserNavigation.PROFILE_ROUTE)
        label.contains("卡密") -> navigateSingleTop(payRoute())
    }
}

/** H5 支付交给系统浏览器：客户端不再开支付 WebView，避免第二套 JS 桥。 */
internal fun Context.openPaymentUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/**
 * 会员关闭时点击售卖入口的统一提示。
 *
 * 入口可能还在（我的页会员卡保留、历史深链、旧底栏缓存），但购买通道不可达；
 * 直接 Toast，不跳会员页、不假装开通成功。
 */
internal fun Context.showMemberClosed() {
    Toast.makeText(this, "会员开通已关闭", Toast.LENGTH_SHORT).show()
}
