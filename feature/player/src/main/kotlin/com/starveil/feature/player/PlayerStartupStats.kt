package com.starveil.feature.player

/**
 * 播放启动耗时采样（进程内，只保留最近 [WINDOW] 次）。
 *
 * 记的是 `prepare() → STATE_READY`：这段就是用户体感的"点了要等多久才有画面"。
 * 短剧竖滑高频切集，所以 P50 比平均值有意义（少数慢样本会拉偏平均）。
 *
 * 只存内存、不落盘、不上报：等接入统计 SDK 或后端埋点时，把 [record] 里的样本
 * 转发出去即可，这里不改调用方。
 */
internal object PlayerStartupStats {

    private const val WINDOW = 30

    private val samples = ArrayDeque<Long>()
    private var lastMs: Long? = null

    /** 命中次数（供后续算预热命中率用）。 */
    var total: Int = 0
        private set

    val latest: Long? get() = lastMs

    fun record(ms: Long) {
        if (ms < 0) return
        lastMs = ms
        total += 1
        // 这是"点了到出画面"的真实耗时，排查卡顿时以它为准
        PlayerLog.warn { "起播首帧 ${ms}ms" }
        if (samples.size >= WINDOW) samples.removeFirst()
        samples.addLast(ms)
    }

    fun p50(): Long? = percentile(50)

    fun p90(): Long? = percentile(90)

    fun reset() {
        samples.clear()
        lastMs = null
        total = 0
    }

    /** 一行摘要，交给 logcat/调试面板用；空采样返回 null。 */
    fun summary(): String? {
        val p50 = p50()
        val p90 = p90()
        return if (p50 != null && p90 != null) "startup p50=${p50}ms p90=${p90}ms n=$total" else null
    }

    private fun percentile(percent: Int): Long? {
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        val index = ((sorted.size - 1) * percent / 100).coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
}
