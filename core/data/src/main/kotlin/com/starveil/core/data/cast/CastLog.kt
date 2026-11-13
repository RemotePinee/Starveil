package com.starveil.core.data.cast

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * 投屏链路唯一的日志出口（与 `PlayerLog` 同一套约定）。
 *
 * - **只有调试包输出**：`ApplicationInfo.FLAG_DEBUGGABLE` 决定开关，正式包里连字符串
 *   拼接都不会发生。不用 `Log.isLoggable`：它在不少 ROM 上对 DEBUG 级别恒为 false，
 *   之前正是这个把诊断日志全吞了。
 * - **不记地址正文**：只记 host、数量、SOAP 状态码与错误码。投屏地址带签名票据。
 * - 平台日志调用在本文件集中一处，闸门需要这一行带豁免标记。
 */
internal object CastLog {

    private const val TAG = "StarveilCast"

    @Volatile
    private var enabled = false

    /** 由装配层在创建仓库时调用；拿不到 Application 信息就保持关闭。 */
    fun install(context: Context) {
        enabled = runCatching {
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        }.getOrDefault(false)
    }

    fun debug(message: () -> String) {
        if (!enabled) return
        Log.i(TAG, message()) // security-baseline-allow: 投屏链路唯一日志出口，仅调试包输出
    }

    /** 地址只留主机名，路径与查询串里的签名一律丢弃。 */
    fun hostOf(url: String?): String {
        val host = url?.substringAfter("://")?.substringBefore('/')?.substringBefore('?')
        return host?.takeIf { it.isNotBlank() } ?: "<无 host>"
    }
}
