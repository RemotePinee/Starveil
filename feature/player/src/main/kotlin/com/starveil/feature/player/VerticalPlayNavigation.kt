package com.starveil.feature.player

/**
 * 竖屏播放路由。供 :app 的 NavHost 汇总注册（AGENTS.md 第 8 节）。
 *
 * 与横屏播放页分开：横屏页要选集/线路/倍速，竖屏页按集号与断点起播，参数集不同，
 * 合成一条路由会让两边的默认值与解析互相污染。
 */

/** 路由前缀。与 [PLAYER_ROUTE] 并列，不互相嵌套。 */
const val VERTICAL_PLAY_ROUTE = "vertical_play"

/** 起始集数：后端 nid 1 起（`PlayUrl.episodeNumber`），不是数组下标。 */
const val FIRST_EPISODE_NUMBER = 1

/** 竖屏页只播第一个播放源（短剧约定"单源多集"），下标固定 0。 */
const val VERTICAL_PLAY_SOURCE_INDEX = 0

/** 集数参数名。与横屏的 `episodeIndex` 区分：这里存的是**集号**，不是下标。 */
const val ARG_EPISODE_NUMBER = "episode"

/** 初次指定集的断点位置，单位毫秒；app 注册为 NavType.LongType。 */
const val ARG_VERTICAL_RESUME_MS = "resumeMs"

/** NavHost 注册的模式串。`vodId` 复用横屏播放页的同名参数常量。 */
const val VERTICAL_PLAY_ROUTE_PATTERN =
    "$VERTICAL_PLAY_ROUTE/{$ARG_VOD_ID}?$ARG_EPISODE_NUMBER={$ARG_EPISODE_NUMBER}" +
        "&$ARG_VERTICAL_RESUME_MS={$ARG_VERTICAL_RESUME_MS}"

/** 生成跳转地址。省略集号/断点时从第 1 集、0 毫秒起播。 */
fun verticalPlayRoute(vodId: Int, episode: Int = FIRST_EPISODE_NUMBER, resumeMs: Long = 0L): String =
    "$VERTICAL_PLAY_ROUTE/$vodId?$ARG_EPISODE_NUMBER=$episode" +
        "&$ARG_VERTICAL_RESUME_MS=${resumeMs.coerceAtLeast(0L)}"

/**
 * 解析路由里的集号。
 *
 * 非法（空串、非数字、0 与负数）与缺失一律回落到第 1 集：深链写错时
 * 用户应该落到"能播的那一集"，而不是看到空白页（同 `ParseRules` 的容错口径）。
 */
fun verticalPlayEpisode(raw: String?): Int =
    raw?.trim()?.toIntOrNull()?.let(::verticalPlayEpisode) ?: FIRST_EPISODE_NUMBER

/** NavType.IntType 取出来的值走同一个夹取规则。 */
fun verticalPlayEpisode(raw: Int?): Int =
    raw?.takeIf { it >= FIRST_EPISODE_NUMBER } ?: FIRST_EPISODE_NUMBER
