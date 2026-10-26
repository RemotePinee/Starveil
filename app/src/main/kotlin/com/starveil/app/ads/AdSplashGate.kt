package com.starveil.app.ads

import android.content.Context
import com.starveil.core.common.ad.AdPlacement
import com.starveil.core.common.ad.AdPlacements

/**
 * 「这次冷启动要不要为 SDK 开屏停一下」的本地标记。
 *
 * 为什么需要它：[SplashAdActivity] 是 launcher，而它开屏前必须先拉一次启动配置才知道
 * 后台有没有配 SDK 开屏位。若每次都等这个网络请求，**没配广告的用户也要多等一次请求**
 * ——那就不是"不配置就无感"了。
 *
 * 所以把判断结果落在 SharedPreferences 里，下次冷启动**同步读**（微秒级），
 * 没配就直接跳主界面，一个网络请求都不发。DataStore 是 suspend 的，赶不上第一帧。
 *
 * 判定口径与 `AdPlatformConfig.ready` 一致：平台配齐了 appId **且**填了开屏 adUnitId，
 * 才算"有 SDK 开屏"。只有直投素材时不算——那种开屏由 `AdHost` 在首页画，不需要这个页。
 */
internal object AdSplashGate {

    private const val PREFS = "starveil_ad_splash"
    private const val KEY_SDK_SPLASH = "sdk_splash_configured"
    /** 直投开屏素材快照：imageUrl / clickUrl / title / seconds。 */
    private const val KEY_DIRECT_IMAGE = "direct_splash_image"
    private const val KEY_DIRECT_CLICK = "direct_splash_click"
    private const val KEY_DIRECT_TITLE = "direct_splash_title"
    private const val KEY_DIRECT_SECONDS = "direct_splash_seconds"
    /** 快照是否来自一份真实启动配置（而不是"从没拉到过"）。 */
    private const val KEY_KNOWN = "startup_known"
    /**
     * 启动配置落地后调用，把"下次要不要停"写进去。
     *
     * **自定义（直投）开屏优先**：只要后台填了开屏图片，这里就写 false ——
     * 否则用户会先看一遍 SDK 的开屏广告、进首页又看一遍自己的图，连看两次。
     * 想用平台开屏就把图片留空。
     */
    fun record(context: Context, placements: AdPlacements) {
        val needed = placements.splash.imageUrl.isBlank() &&
            placements.platform.ready &&
            placements.platform.tradPlus.splashSlot.isNotBlank()
        preferences(context).edit()
            .putBoolean(KEY_SDK_SPLASH, needed)
            // 直投开屏画在首页上，首帧就得知道有没有素材，所以素材本身也留一份
            .putString(KEY_DIRECT_IMAGE, placements.splash.imageUrl)
            .putString(KEY_DIRECT_CLICK, placements.splash.clickUrl)
            .putString(KEY_DIRECT_TITLE, placements.splash.title)
            .putInt(KEY_DIRECT_SECONDS, placements.splash.seconds)
            // "这份快照是真实配置写的"：区分「后台确实没配直投开屏」和「还没拉到过配置」
            .putBoolean(KEY_KNOWN, true)
            .apply()
    }

    /**
     * 上次下发过的直投开屏素材。
     *
     * 冷启动第一帧就要拿它画遮罩：配置要等网络，而首页 Compose 首帧跑在配置回来之前，
     * 不等的话用户会先看见首页、广告再盖上来（就是"闪一下首页"）。
     */
    fun splash(context: Context): AdPlacement? {
        val prefs = preferences(context)
        val image = prefs.getString(KEY_DIRECT_IMAGE, "").orEmpty()
        if (image.isBlank()) return null
        return AdPlacement(
            enabled = true,
            imageUrl = image,
            clickUrl = prefs.getString(KEY_DIRECT_CLICK, "").orEmpty(),
            title = prefs.getString(KEY_DIRECT_TITLE, "").orEmpty(),
            seconds = prefs.getInt(KEY_DIRECT_SECONDS, 0),
        )
    }

    /** 冷启动第一帧前同步读；默认 false，即"没配过就不停"。 */
    fun needed(context: Context): Boolean = preferences(context).getBoolean(KEY_SDK_SPLASH, false)

    /**
     * 有没有拿到过一份真实的启动配置。
     *
     * 与 [splash] 是两件事：后台可能确实没配直投开屏（splash 为空但 known 为真），
     * 那种情况冷启动不必再等配置；而首装 / 清数据后 known 为假，此时强制进首页就会
     * 先露一帧首页再被广告盖住。
     */
    fun known(context: Context): Boolean = preferences(context).getBoolean(KEY_KNOWN, false)

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
