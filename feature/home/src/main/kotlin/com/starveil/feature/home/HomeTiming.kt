package com.starveil.feature.home

import android.os.SystemClock
import android.util.Log

/**
 * 首页转场计时（临时诊断用，验证完即删）。
 *
 * 只回答一个问题：切回首页时，是"页面晚合成"还是"图晚到"。两种原因改法完全不同，
 * 之前靠猜已经改错过两次。
 */
internal object HomeTiming {

    private const val TAG = "HomeTiming"

    fun mark(label: String) {
        Log.i(TAG, "$label t=${SystemClock.elapsedRealtime()}") // security-baseline-allow: 临时诊断日志
    }

    fun since(start: Long, label: String) {
        mark("$label +${SystemClock.elapsedRealtime() - start}ms")
    }
}
