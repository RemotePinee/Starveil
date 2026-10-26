package com.starveil.app.ads

import com.starveil.core.common.ad.AdGateway
import com.starveil.core.common.ad.AdOutcome
import com.starveil.core.common.ad.AdPlacements
import com.starveil.core.common.ad.AdSlot

/**
 * 直投广告网关：没有接第三方 SDK 时的实现。
 *
 * 与 [NoopAdProvider] 的区别在 [isReady]：这里看后台配置，配上素材就为真，
 * 于是「看广告解锁」按钮会出现，真正的"播放广告"由 app 层的 [AdHost] 浮层完成。
 * 本类不做任何 SDK 初始化、不发网络请求，[show] 只把"平台侧无 SDK"说清楚。
 *
 * 接 TopOn / TradPlus 时新增实现即可，业务层不受影响（AGENTS.md §3.3 的接口约定）。
 */
class LocalAdGateway(
    private val placements: () -> AdPlacements,
) : AdGateway {

    override fun isReady(slot: AdSlot): Boolean {
        val placements = placements()
        // 激励位额外认"配了奖励视频"：视频链路由服务端签发会话，客户端只负责播，
        // 地址非空就说明这个入口有东西可看（够不够资格解锁仍由服务端 complete 决定）。
        if (slot == AdSlot.REWARD && placements.hasRewardVideo) return true
        return placements.of(slot).drawable
    }

    /** 直投素材随启动配置一起到达，没有预加载概念。 */
    override fun preload(slot: AdSlot) = Unit

    /**
     * 直投位没有平台回调，[AdOutcome.Completed] 只表示"网关放行给浮层去播"。
     * 解锁判定仍以服务端为准。
     */
    override suspend fun show(slot: AdSlot): AdOutcome =
        if (isReady(slot)) AdOutcome.Completed else AdOutcome.NoFill
}
