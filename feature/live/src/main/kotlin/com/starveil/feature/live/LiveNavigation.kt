package com.starveil.feature.live

/**
 * 直播页路由。供 :app 的 NavHost 汇总注册（AGENTS.md 第 8 节）。
 *
 * 直播只有一页：列表与播放区同屏（历史版本点卡片会开 X5 网页，见 ），
 * 所以没有带参数的子路由，也没有 deep link 参数。
 */
const val LIVE_ROUTE = "live"
