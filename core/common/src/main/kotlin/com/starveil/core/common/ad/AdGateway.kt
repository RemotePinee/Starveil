package com.starveil.core.common.ad

/**
 * 广告位。五个位与后台「运营设置 → 广告」一一对应，客户端不另造位置。
 *
 * 当前没有接第三方 SDK，能画出来的只有直投资材（图片）；位 ID 非空但没素材时
 * 由各页面自行降级为隐藏，不占布局（见 [AdPolicy] 与 `AdPlacement`）。
 */
enum class AdSlot {
    /** 冷启动首帧全屏图。 */
    SPLASH,

    /** 首页底部横幅。 */
    BANNER,

    /** 剧场页海报墙插播。 */
    FEED,

    /** 试看解锁的激励视频。 */
    REWARD,

    /** 播放器暂停态贴片。 */
    PAUSE,
}

sealed interface AdOutcome {
    data object Completed : AdOutcome
    data object Skipped : AdOutcome
    data object NoFill : AdOutcome
    data class Failed(val reason: String) : AdOutcome
}

/**
 * 广告出口。业务层只依赖这个接口；`:app` 提供 Local / TopOn / TradPlus 实现。
 * [show] 返回的 Completed 只代表"平台侧看完"，**不是放行依据**：
 * 真正的解锁必须由服务端校验 SSV 回调后换签。
 */
interface AdGateway {
    fun isReady(slot: AdSlot): Boolean
    fun preload(slot: AdSlot)
    suspend fun show(slot: AdSlot): AdOutcome
}

/** 广告策略：纯函数，供 UI 与初始化器共用。 */
object AdPolicy {
    /** VIP 免广告：VIP 一律不展示、不初始化；开关关闭或没有广告位同样不展示。 */
    fun shouldShowAds(isVip: Boolean, adEnabled: Boolean): Boolean = !isVip && adEnabled

    /**
     * 这个位此刻是否有东西可画：总开关开、位开启，且有直投资材。
     *
     * 位 ID 非空但没有图片时返回 false —— 那是给接入 SDK 之后用的，
     * 现在画不出来，占着位置只会留一块空白。
     */
    fun drawable(enabled: Boolean, imageUrl: String?): Boolean =
        enabled && !imageUrl.isNullOrBlank()
}
