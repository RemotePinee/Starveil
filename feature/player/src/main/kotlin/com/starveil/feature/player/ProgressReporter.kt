package com.starveil.feature.player

import com.starveil.core.common.AppResult
import com.starveil.core.model.ProgressRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 观看时长记账与上报。
 *
 * 规则全部来自 [ProgressRules]（周期 30 秒、按真实间隔折算秒数）。
 * 是 的 30 秒 Timer + 每周期 `+= 60`
 * （按 2 倍速虚增），这里按采样间隔累加，口径变化见 ProgressRules 类注释。
 */
internal class ProgressReporter(
    private val clock: PlayerClock,
    private val sink: ProgressSink,
    private val scope: CoroutineScope,
) {

    private var lastTickAtMs: Long? = null
    private var lastReportAtMs: Long? = null
    private var watchedMs: Long = 0L

    /** 起播：记基准时刻，避免第一个采样点就上报 0 秒。 */
    fun onPlaybackStarted() {
        val nowMs = clock.nowMs()
        lastTickAtMs = nowMs
        lastReportAtMs = nowMs
        watchedMs = 0L
    }

    /** 采样一次；够一个周期就上报（暂停期间不累计）。 */
    fun onTick(isPlaying: Boolean, sourceIndex: Int, episodeIndex: Int) {
        val nowMs = clock.nowMs()
        accumulate(nowMs, isPlaying)
        if (!ProgressRules.shouldReport(lastReportAtMs, nowMs)) return
        val seconds = ProgressRules.secondsToReport(watchedMs)
        lastReportAtMs = nowMs
        watchedMs = 0L
        scope.launch { report(sourceIndex, episodeIndex, seconds) }
    }

    /** 拖动进度：重置采样点，避免把拖动间隔算成观看时长。 */
    fun onSeek() {
        lastTickAtMs = null
    }

    fun reset() {
        lastTickAtMs = null
        lastReportAtMs = null
        watchedMs = 0L
    }

    private fun accumulate(nowMs: Long, isPlaying: Boolean) {
        val previous = lastTickAtMs
        lastTickAtMs = nowMs
        if (!isPlaying || previous == null) return
        watchedMs += (nowMs - previous).coerceAtLeast(0L)
    }

    private suspend fun report(sourceIndex: Int, episodeIndex: Int, seconds: Long) {
        val result = sink.reportWatchedSeconds(sourceIndex, episodeIndex, seconds)
        if (result is AppResult.Failure) {
            // 上报失败不打断播放：把没送出去的秒数还回去，下个周期一起补报
            watchedMs += seconds * MILLIS_PER_SECOND
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
