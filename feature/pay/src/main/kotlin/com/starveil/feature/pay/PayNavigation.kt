package com.starveil.feature.pay

/**
 * 支付页路由，供 `:app` 的 NavHost 汇总注册（AGENTS.md 第 8 节：feature 只提供路由，
 * 不持有 NavController）。
 *
 * 支付曾是 ViewPager 两页（充值 / 会员），单集购买则藏在播放页的 `payPlay()` 里。
 * 新版合成一个目的地，用 [ARG_PAY_MODE] 区分会员与单集，避免两套支付 UI。
 */
const val PAY_ROUTE = "pay"

/** 会员/充值（`membership`）或单集购买（`episode`）。 */
const val ARG_PAY_MODE = "payMode"

/** 单集购买的影片 id。 */
const val ARG_VOD_ID = "vodId"

/** 单集购买的集数（后端 `nid`，1 起）。 */
const val ARG_EPISODE_NUMBER = "episodeNumber"

/** 播放源下标，0 起。 */
const val ARG_SOURCE_INDEX = "sourceIndex"

const val PAY_MODE_MEMBERSHIP = "membership"
const val PAY_MODE_EPISODE = "episode"

/** NavHost 注册用的带占位符路由；参数全部可选，缺省即会员模式。 */
const val PAY_ROUTE_PATTERN: String =
    "$PAY_ROUTE?$ARG_PAY_MODE={$ARG_PAY_MODE}" +
        "&$ARG_VOD_ID={$ARG_VOD_ID}" +
        "&$ARG_EPISODE_NUMBER={$ARG_EPISODE_NUMBER}" +
        "&$ARG_SOURCE_INDEX={$ARG_SOURCE_INDEX}"

/**
 * 生成跳转地址。两个入口：
 * - [payRoute]()：会员/充值
 * - [payRoute] 带影片与集数：单集购买（旧 直调接口的那条路）
 */
fun payRoute(
    mode: String = PAY_MODE_MEMBERSHIP,
    vodId: Int = 0,
    episodeNumber: Int = 0,
    sourceIndex: Int = 0,
): String = "$PAY_ROUTE?$ARG_PAY_MODE=$mode" +
    "&$ARG_VOD_ID=$vodId" +
    "&$ARG_EPISODE_NUMBER=$episodeNumber" +
    "&$ARG_SOURCE_INDEX=$sourceIndex"

/** 从导航参数还原 [PayArgs]；缺参数时按会员模式处理。 */
fun payArgs(
    mode: String?,
    vodId: Int,
    episodeNumber: Int,
    sourceIndex: Int,
): PayArgs = PayArgs(
    mode = PayMode.fromRoute(mode),
    vodId = vodId,
    episodeNumber = episodeNumber,
    sourceIndex = sourceIndex,
)
