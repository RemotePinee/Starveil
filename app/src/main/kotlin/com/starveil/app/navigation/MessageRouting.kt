package com.starveil.app.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.navigation.NavHostController
import com.starveil.core.model.AppMessage
import com.starveil.core.model.MessageAction
import com.starveil.feature.detail.detailRoute
import com.starveil.feature.home.FILTER_ROUTE

/**
 * 点一条消息之后去哪，由后台配置的 `action_type + action_value` 决定。
 *
 * 三种去向都走现成导航，不为此新增路由：
 * - `vod` → 详情页（与列表点海报同一条路径）
 * - `type` → 筛选页（带分类 id）
 * - `url` → 系统浏览器（站外链接不内嵌 WebView：混合内容与证书放行是明令禁止的）
 *
 * 解析失败的参数一律当作"不跳"，避免因为后台填错一个 id 让点击变成崩溃。
 */
internal fun NavHostController.openMessageAction(context: Context, message: AppMessage) {
    when (val action = message.action) {
        is MessageAction.None -> Unit
        is MessageAction.Vod -> navigate(detailRoute(action.id))
        // 片库路由不带参数（分类 id 目前固定传 0），所以这里只能打开"全部"。
        is MessageAction.Type -> navigate(FILTER_ROUTE)
        is MessageAction.Url -> openExternal(context, action.url)
    }
}

/** 站外链接交给系统浏览器；只接受 http(s)，其它 scheme 一律忽略。 */
private fun openExternal(context: Context, url: String) {
    val uri = Uri.parse(url)
    if (uri.scheme?.lowercase() !in EXTERNAL_SCHEMES) return
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private val EXTERNAL_SCHEMES = setOf("http", "https")
