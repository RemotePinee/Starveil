package com.starveil.app.ads

import android.content.Context

/**
 * 广告隐私同意状态（本地，同步读）。
 *
 * 为什么必须是本地同步布尔：同意弹窗出现在首帧之后，而聚合 SDK 的初始化发生在
 * `AdProviders.create`（`AppContainer.updateAdPlacements`）。如果同意状态要等网络或
 * DataStore 异步读，第一帧就会带着"未同意"的默认值把 SDK 初始化掉——这正是
 * `StarveilApplication` 注释里承诺不做的事。
 *
 * 取值语义：
 * - `granted = true`：允许初始化聚合 SDK；
 * - `asked = true` 且 `granted = false`：用户选择过"暂不"，只展示直投素材，不再弹窗。
 */
internal object AdConsentStore {

    private const val PREFS = "starveil_ad_consent"
    private const val KEY_GRANTED = "granted"
    private const val KEY_ASKED = "asked"

    fun isGranted(context: Context): Boolean = prefs(context).getBoolean(KEY_GRANTED, false)

    /** 还没问过才弹：用户点过"暂不"之后不该每次冷启动都拦一次。 */
    fun needsPrompt(context: Context): Boolean = !prefs(context).getBoolean(KEY_ASKED, false)

    fun grant(context: Context) {
        prefs(context).edit().putBoolean(KEY_GRANTED, true).putBoolean(KEY_ASKED, true).apply()
    }

    fun decline(context: Context) {
        prefs(context).edit().putBoolean(KEY_GRANTED, false).putBoolean(KEY_ASKED, true).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
