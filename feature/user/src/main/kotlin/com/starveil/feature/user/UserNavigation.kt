package com.starveil.feature.user

/**
 * 用户中心相关路由，供 `:app` 的 NavHost 汇总（AGENTS.md 第 8 节：feature 只提供路由，
 * 不自己持有 NavController）。
 *
 * 没有路由： 直接 `replace()` 一个 Fragment，
 * 登录/注册/找回密码是三个独立 Activity。新实现全部是同一 NavHost 里的目的地。
 */
object UserNavigation {

    /** 用户中心（多皮肤）。 */
    const val USER_CENTER_ROUTE = "user_center"

    /** 登录页。 */
    const val LOGIN_ROUTE = "login"

    /** 注册页。 */
    const val REGISTER_ROUTE = "register"

    /** 找回密码。 */
    const val RESET_PASSWORD_ROUTE = "reset_password"

    /** 我的收藏。 */
    const val FAVORITE_ROUTE = "favorite"

    /** 个人资料。 */
    const val PROFILE_ROUTE = "profile"

    /** 金币提现。 */
    const val WITHDRAW_ROUTE = "withdraw"

    /** 积分中心（签到 + 今日任务）。历史版本没有这个页面，积分只能后台手动加。 */
    const val POINTS_ROUTE = "points"

    /** 观看记录（`v1.user/videoProgressList` 最近 10 条）。 */
    const val HISTORY_ROUTE = "history"

    /** 反馈（`v1.gbook` 留言板）。 */
    const val FEEDBACK_ROUTE = "feedback"
}
