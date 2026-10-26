package com.starveil.feature.rank

/**
 * 榜单页路由。供 :app 的 NavHost 汇总注册（AGENTS.md 第 8 节）。
 *
 * 页面内不做跨模块跳转：点击影片时通过 `RankScreen` 的 `onVodClick(vodId)` 上抛，
 * 由 :app 决定进播放页还是详情页。跨页面跳转靠导航，不靠模块依赖。
 */
const val RANK_ROUTE = "rank"
