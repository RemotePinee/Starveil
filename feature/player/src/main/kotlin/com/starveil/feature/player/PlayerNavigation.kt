package com.starveil.feature.player

/**
 * 播放页路由。供 :app 的 NavHost 汇总注册（AGENTS.md 第 8 节）。
 *
 * 跨页面跳转靠导航不靠模块依赖，所以路径常量只能放在 feature 自己的导航文件里。
 */

const val PLAYER_ROUTE = "player"

/** 影片 id（Vod.id）。播放页靠它取详情与播放源。 */
const val ARG_VOD_ID = "vodId"

/** 播放源下标（0 起，对应 Vod.playSources 下标，不是后端 sid）。 */
const val ARG_SOURCE_INDEX = "sourceIndex"

/** 集数下标（0 起，对应 PlaySource.episodes 下标，不是后端 nid）。 */
const val ARG_EPISODE_INDEX = "episodeIndex"

/** NavHost 注册的模式串，可选参数带默认值。 */
const val PLAYER_ROUTE_PATTERN =
    "$PLAYER_ROUTE/{$ARG_VOD_ID}?$ARG_SOURCE_INDEX={$ARG_SOURCE_INDEX}" +
        "&$ARG_EPISODE_INDEX={$ARG_EPISODE_INDEX}"

/** 生成跳转地址；缺省从第一个源的第一集起播。 */
fun playerRoute(vodId: Int, sourceIndex: Int = 0, episodeIndex: Int = 0): String =
    "$PLAYER_ROUTE/$vodId?$ARG_SOURCE_INDEX=$sourceIndex&$ARG_EPISODE_INDEX=$episodeIndex"
