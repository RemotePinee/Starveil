package com.starveil.app.ads

import android.app.Activity
import android.content.Context
import com.starveil.core.common.ad.AdGateway
import com.starveil.core.common.ad.AdPlacements
import com.starveil.core.common.ad.AdPlatformName
import com.starveil.core.common.ad.AdPlatformConfig

/**
 * 按后端选择的平台造广告出口。
 *
 * 选择的优先级：**平台 SDK 配齐了就先用 SDK，否则退回直投素材**。
 * 退回是按位发生的——`isReady` 由各实现自己判，页面因此可能出现
 * "激励位走 SDK、横幅位还画直投图"的混合状态，这是允许的，也符合
 * "填充率不够就补直投"的运营预期。
 *
 * 两套 SDK 的初始化都只在这里触发一次：隐私同意与非 VIP 的判断在
 * `StarveilNavHost` 里做（`AdPolicy.shouldShowAds`），本类只管按选择构造。
 */
object AdProviders {

    /**
     * @param activity 展示全屏广告需要宿主 Activity；冷启动时可能还没有，
     *   所以传选择器而不是实例，取到 null 时各实现自己降级。
     */
    fun create(
        context: Context,
        placements: AdPlacements,
        activity: () -> Activity?,
        /**
         * 激励请求要带的身份（见 [RewardRequestContext]）：SSV 回调靠它认人、
         * 靠 custom_data 把 `vodId:episodeNumber` 带回来。默认空 = 不注入。
         */
        contextProvider: () -> AdRequestContext? = { null },
    ): AdGateway {
        // 未同意隐私政策前不初始化任何聚合 SDK：初始化即开始采集设备标识。
        // 降级后的直投素材不采集信息，功能上只是没有 SDK 广告位。
        val platform =
            if (AdConsentStore.isGranted(context)) placements.platform else AdPlatformConfig.NONE
        if (!platform.ready) return LocalAdGateway { placements }
        return when (platform.name) {
            AdPlatformName.TRADPLUS ->
                if (TradPlusAdProvider.init(context, platform.tradPlus)) {
                    TradPlusAdProvider(context, platform.tradPlus, activity, contextProvider)
                } else {
                    LocalAdGateway { placements }
                }

            AdPlatformName.NOOP -> LocalAdGateway { placements }
        }
    }
}
