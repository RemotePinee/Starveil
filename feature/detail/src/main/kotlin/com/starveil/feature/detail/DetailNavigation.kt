package com.starveil.feature.detail

/**
 * 详情页路由。供 :app 的 NavHost 汇总注册（AGENTS.md 第 8 节）。
 *
 * 跨页面跳转靠导航不靠模块依赖，所以路径常量只能放在 feature 自己的导航文件里。
 * 详情与播放曾挤在同一个 Activity 里：详情是一个 1163 行的 Fragment，
 * 播放是它的宿主。新实现把两者拆成独立目的地。
 */

const val DETAIL_ROUTE = "detail"

/** 影片 id（Vod.id）。详情、收藏、播放都靠它。 */
const val ARG_VOD_ID = "vodId"

/** 起始播放源下标（0 起）；[DETAIL_INDEX_UNSET] 表示按详情页自己的默认值选。 */
const val ARG_SOURCE_INDEX = "src"

/** 起始集下标（0 起）；[DETAIL_INDEX_UNSET] 表示按详情页自己的默认值选。 */
const val ARG_EPISODE_INDEX = "ep"

/** 没指定源/集：进入详情页时选第 1 源第 1 集。 */
const val DETAIL_INDEX_UNSET = -1

/** NavHost 注册的模式串。两个下标可选：只有观看记录点进来才带。 */
const val DETAIL_ROUTE_PATTERN =
    "$DETAIL_ROUTE/{$ARG_VOD_ID}?$ARG_SOURCE_INDEX={$ARG_SOURCE_INDEX}&$ARG_EPISODE_INDEX={$ARG_EPISODE_INDEX}"

/**
 * 生成跳转地址。
 *
 * 带 [sourceIndex] / [episodeIndex] 时详情页会预选这一源这一集：观看记录靠它回到
 * "上次那条线路那一集"，多源影片不会又跳回第一源。
 */
fun detailRoute(
    vodId: Int,
    sourceIndex: Int = DETAIL_INDEX_UNSET,
    episodeIndex: Int = DETAIL_INDEX_UNSET,
): String = if (sourceIndex < 0 || episodeIndex < 0) {
    "$DETAIL_ROUTE/$vodId"
} else {
    "$DETAIL_ROUTE/$vodId?$ARG_SOURCE_INDEX=$sourceIndex&$ARG_EPISODE_INDEX=$episodeIndex"
}
