package com.starveil.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 试看判定结果，来自 `v1.user/checkVodTrySee`（
 *
 * 外层已经是 `{code,msg,data}` 壳，`data` 里**还有一层自己的 code/msg**：
 * 后端 bean 自己声明了 code/msg，而播放页取的又是 `result.getData()`
 * 且旧样例是 `code:1` / `msg:"开启试看模式"`，
 * 说明内层 code 是播放页提示码，不是成功判定，别拿来判 `== 200`。
 *
 * - `trysee`：**已废弃**。服务端已改成单一付费墙开关，不再下发有效秒数；
 *   字段保留只为兼容旧响应，客户端不拿它拼文案、也不当作放行时长；
 * - `status`：单一开关，0 免费 / 1 需解锁（2 是历史取值，服务端不再下发）；
 * - `user_video`：剩余免费次数，> 0 时即使 status 是 1 也放行；
 * - `member_enabled` / `can_vip` / `can_points` / `can_ad` / `points_price`：
 * 服务端给播放器浮层的**按钮能力位**。会员关闭时 `can_vip=0`，客户端据此少渲染一个按钮，
 * 不再自己拿开关判断；`can_ad` 是广告解锁预留位，接入 SSV 前恒 0。
 *
 * 前三个数字后端 bean 都声明为 int，这里按 int 收；后端若改发字符串会走解析失败，
 * 而不是静默变成 0。新字段用可空类型，老后端不下发时由 mapper 回退旧行为。
 */
@Serializable
data class TrySeeDto(
    @SerialName("code") val code: Int = 0,
    @SerialName("msg") val msg: String? = null,
    @SerialName("trysee") val trySeeMinutes: Int = 0,
    @SerialName("status") val status: Int = 0,
    @SerialName("user_video") val freeCount: Int = 0,
    @SerialName("member_enabled") val memberEnabled: Int? = null,
    @SerialName("can_vip") val canVip: Int? = null,
    @SerialName("can_points") val canPoints: Int? = null,
    @SerialName("can_ad") val canAd: Int? = null,
    @SerialName("points_price") val pointsPrice: Int = 0,
)
