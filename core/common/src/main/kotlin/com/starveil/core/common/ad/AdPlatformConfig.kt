package com.starveil.core.common.ad

/**
 * 聚合平台标识。后端「运营设置 → 广告平台」决定用哪个，客户端不硬编码。
 *
 * 现在只有 TradPlus 一家：TopOn 的 adapter 不公开托管（Maven 上全是 404，要后台
 * 打包生成 aar），在没有 aar 之前它配了也不出广告，所以整条链路都摘掉了——
 * 依赖、provider、后端分组一起删，不留"看起来能用其实填充率 0"的空壳。
 *
 * 以后要加平台：这里加枚举 + 一个 Config 类 + 一个 AdGateway 实现，其余不用动。
 */
enum class AdPlatformName {
    /** 没接 SDK，只用后台直投资材。 */
    NOOP,
    TRADPLUS,

    ;

    companion object {
        /** 认不出的平台名按 NOOP 处理：宁可不出广告，也不要初始化一个来路不明的 SDK。 */
        fun parse(raw: String?): AdPlatformName = when (raw?.trim()?.lowercase()) {
            "tradplus" -> TRADPLUS
            else -> NOOP
        }
    }
}

/**
 * TradPlus 配置。没有 appKey：它的初始化签名是 `initSdk(context, appId)`，只有两个参数。
 *
 * 五个 `*Slot` 是后台的 adUnitId，空串表示该位不接平台、继续用直投资材。
 * 注意类型是在 TradPlus 后台建广告位时就绑死的（暂停位=插屏、信息流位=原生），
 * 建错只会得到 `errorCode 12 / no config`。
 */
data class TradPlusConfig(
    val appId: String = "",
    val splashSlot: String = "",
    val rewardSlot: String = "",
    val feedSlot: String = "",
    val pauseSlot: String = "",
    val bannerSlot: String = "",
) {
    val initable: Boolean get() = appId.isNotBlank()

    fun slotOf(slot: AdSlot): String = when (slot) {
        AdSlot.SPLASH -> splashSlot
        AdSlot.REWARD -> rewardSlot
        AdSlot.FEED -> feedSlot
        AdSlot.PAUSE -> pauseSlot
        AdSlot.BANNER -> bannerSlot
    }

    companion object {
        val NONE = TradPlusConfig()
    }
}

/** 平台选择 + 凭证。后端一次下发，客户端只初始化选中的那一个。 */
data class AdPlatformConfig(
    val name: AdPlatformName = AdPlatformName.NOOP,
    val tradPlus: TradPlusConfig = TradPlusConfig.NONE,
) {
    /** 当前选中的平台是否配齐了初始化凭证；没配齐时退化成直投。 */
    val ready: Boolean get() = name == AdPlatformName.TRADPLUS && tradPlus.initable

    companion object {
        val NONE = AdPlatformConfig()
    }
}
