package com.starveil.feature.player

import android.util.Log
import androidx.core.net.toUri
import com.starveil.core.model.PlayerInfo

/**
 * 播放链路唯一的日志出口。
 *
 * 集中成一个对象是 docs/security.md 的要求，不是风格偏好：
 * - 第 8 节：release 关掉全部调试日志。这里 `BuildConfig.DEBUG` 为假时首行返回，
 *   消息用 lambda 传，连字符串拼接都不会发生；
 * - 第 4 节：兜底链路不用裸日志。`verifySecurityBaseline` 因此把 `Log.x(` 列为禁片段，
 *   全工程只留 [warn] 里那一处平台调用，其余代码一律走本对象；
 * - 第 8 节：播放地址、解析站地址不得进日志。地址一律先过 [hostOf]，只留 host，
 *   路径与查询串里的签名、票据不进 logcat。
 */
internal object PlayerLog {

    private const val TAG = "HongguoGate"

    /**
     * 需要看、但不该让播放失败的信息（原来都是直接 `Log.e`）。
     *
     * @param throwable 有异常时附在消息尾部；没有就不拼这一段。
     * @param message 延迟求值：release 下不会执行。
     */
    fun warn(throwable: Throwable? = null, message: () -> String) {
        if (!BuildConfig.DEBUG) return
        val text = if (throwable == null) message() else "${message()}: $throwable"
        Log.e(TAG, text) // security-baseline-allow: 全工程唯一一处平台日志调用，其余代码只能走 PlayerLog
    }

    /** 地址只留 host；解析不出 host 时给个固定标记，避免把原地址兜底打出去。 */
    fun hostOf(url: String?): String {
        val host = url?.toUri()?.host
        return if (host.isNullOrBlank()) "<无 host>" else host
    }

    /**
     * 红果开关下发摘要：两个 ViewModel（长剧 / 竖屏短剧）打同一份内容，放这里免得两边各拼一遍。
     * 只有布尔与正则串，不含任何地址。
     */
    fun describePlayerInfo(prefix: String, info: PlayerInfo?): String =
        "$prefix playerInfo=${info != null}" +
            " hongguo=${info?.hongguoEnabled ?: false}" +
            " match=${info?.hongguoMatch ?: "<null>"}"
}
