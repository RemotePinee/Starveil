package com.starveil.app.dialog

import android.content.Context

/**
 * 弹窗弹出记录，落在 SharedPreferences。
 *
 * 只存 `key -> 上次弹出时间戳`，几十字节的事；换成 DataStore 要多引一个依赖，
 * 而这里既没有并发写也没有响应式读取的需求。
 */
internal class DialogHistoryStore(context: Context) : DialogHistory {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun lastShownAt(key: String): Long = prefs.getLong(key, 0L)

    override fun markShown(key: String, at: Long) {
        prefs.edit().putLong(key, at).apply()
    }

    private companion object {
        const val PREFS_NAME = "app_dialog_history"
    }
}
