package com.starveil.core.model

/**
 * 观看进度上报规则。
 *
 * 来源 （30 秒 Timer）与 `:1795-1803`（viewSeconds）。
 *
 * 每 30 秒给累计值 `+= 60`，也就是按 2 倍速上报观看时长；
 * 后端把这个数当"观看秒数"累计，等于每个用户都虚增一倍。新实现按真实间隔上报，
 * 口径变化写在这里，不做静默修改。
 */
object ProgressRules {

    /** 上报周期（ 的 `1000 * 30`）。 */
    const val REPORT_INTERVAL_MS = 30_000L

    /** 弹幕预取窗口（ 的 `1000 * 10`）。 */
    const val DANMU_PREFETCH_WINDOW_MS = 10_000L

    /** 弹幕拉空后的时间轴推进量（ 的 `+= 30000`）。 */
    const val DANMU_EMPTY_ADVANCE_MS = 30_000L

    /**
     * 距离上次上报是否已经够一个周期。
     *
     * `lastReportAtMs` 为 null 表示还没上报过，此时立刻上报一次（靠
     * `index == 0` 跳过首个 tick，等价于"等到一个周期后再报"）。
     */
    fun shouldReport(lastReportAtMs: Long?, nowMs: Long): Boolean =
        lastReportAtMs == null || nowMs - lastReportAtMs >= REPORT_INTERVAL_MS

    /**
     * 本次要上报的秒数：真实观看间隔，向下取整。
     *
     * 历史版本固定 60 秒（见类注释），这里改成按传入的间隔计算，
     * 暂停期间不累计由调用方保证（只在 STATE_PLAYING 计时）。
     */
    fun secondsToReport(intervalMs: Long): Long =
        if (intervalMs <= 0) 0 else intervalMs / 1_000
}
