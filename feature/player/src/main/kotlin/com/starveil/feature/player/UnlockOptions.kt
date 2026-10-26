package com.starveil.feature.player

import com.starveil.core.model.TrySeeGate

/**
 * 试看拦截的出口：会员 / 积分兑换 / 广告。
 *
 * 三个布尔全部来自服务端能力位（`checkVodTrySee` 的 `can_vip` / `can_points` / `can_ad`），
 * UI 不做业务判断：
 * - 会员体系关闭时服务端给 `can_vip=0`，浮层自然只剩积分（与广告）两个按钮；
 * - 会员体系开启时会员按钮出现，用户可任选一种方式继续看；
 * - [canWatchAd] 还要叠加广告位是否**有东西可播**：直投图片（`drawable`）或直投视频
 *   （启动配置的 `ad_reward_video`）任一配好，`LocalAdGateway.isReady` 就为真。
 *
 * 注意这里只回答"有没有素材"，不回答"看完必然解锁"。直投视频的放行完全由服务端决定：
 * 领会话（`v1.ad/session`）→ 播 → 报进度（`v1.ad/progress`）→ 完成（`v1.ad/complete`），
 * 服务端按"进度够 + 真实经过时间够"自己写解锁记录；客户端随后重查 `checkVodTrySee`。
 * 所以本文件不引入任何"客户端说了算"的判定（AGENTS.md 第 6 节的反模式）。
 */
data class UnlockOptions(
    val canUpgrade: Boolean = true,
    val canRedeemPoints: Boolean = false,
    val canWatchAd: Boolean = false,
    /** 单集所需积分；0 表示后台没配价，按钮文案不显示数字。 */
    val pointsPrice: Int = 0,
)

/**
 * 服务端能力位 → 浮层按钮。
 *
 * [adReady] 是广告网关的 `isReady(AdSlot.REWARD)`：直投图片与直投视频都算就绪，
 * 两个条件都满足才出现广告按钮，避免"网关说有广告、服务端却没配素材"时用户点了没反应。
 */
fun TrySeeGate.unlockOptions(adReady: Boolean = false): UnlockOptions = UnlockOptions(
    canUpgrade = canUpgrade,
    canRedeemPoints = canRedeemPoints,
    canWatchAd = canWatchAd && adReady,
    pointsPrice = pointsPrice,
)
