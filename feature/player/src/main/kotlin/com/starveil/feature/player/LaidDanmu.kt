package com.starveil.feature.player


/**
 * 一条排好轨道的弹幕。`atTimeMs` 永远是弹幕自己的时间，不会被排布算法改动 ——
 * 弹幕必须对上画面，任何"错峰/抖动"都是错的。
 */
internal data class LaidDanmu(
    val text: String,
    val atTimeMs: Long,
    val lane: Int,
    /** 弹幕自身颜色（`#RRGGBB` / `RRGGBB`）；null 用默认白。 */
    val colorHex: String? = null,
)

/** 同轨两条弹幕之间额外留的空隙（像素）。 */
internal const val LANE_GAP_PX = 96f

/**
 * 弹幕排布（纯函数，可单测）。
 *
 * 规则与主流播放器一致：
 * 1. 按时间升序处理，**入场时间不修改**；
 * 2. 一条弹幕占用某轨道直到"它的尾巴完全进入屏幕"为止，即
 *    `freeAt = start + (textWidth + gap) / speed`；这保证了同轨不会追尾（长弹幕更久）；
 * 3. 所有轨道都忙时**丢弃这条**，而不是延后（延后＝对不上画面）或硬挤（＝叠字）。
 *
 * 之所以按整集一次算好而不是按 10 秒窗口算：窗口边界会把轨道状态清零，
 * 结果就是每个窗口开头都叠一批。
 */
/** 排布参数：轨道数、速度、间距、文字宽度测量。收成对象是为了让 [layoutDanmu] 参数不超 5 个。 */
internal class DanmuLayoutConfig(
    val laneCount: Int,
    val speedPxPerSec: Float,
    val gapPx: Float,
    val measureWidth: (String) -> Float,
) {
    val valid: Boolean get() = laneCount > 0 && speedPxPerSec > 0f
}

internal fun layoutDanmu(items: List<DanmuItem>, config: DanmuLayoutConfig): List<LaidDanmu> {
    if (items.isEmpty() || !config.valid) return emptyList()
    val freeAtMs = LongArray(config.laneCount) { Long.MIN_VALUE }
    val out = ArrayList<LaidDanmu>(items.size)
    // 两轮：先本站用户弹幕（priority 0）占轨，再让官方弹幕填剩下的空位。
    // 不能合成一轮按时间排——官方弹幕十秒能有几十条，会把用户弹幕全挤掉。
    items.groupBy { it.priority }.toSortedMap().forEach { (_, group) ->
        out += placeGroup(group, freeAtMs, config)
    }
    // 输出按时间排序：绘制层用二分取可见区间，必须有序
    return out.sortedBy { it.atTimeMs }
}

/** 排一组同优先级弹幕；轨道占用按"上一条尾巴进屏"的时间累积。 */
private fun placeGroup(
    group: List<DanmuItem>,
    freeAtMs: LongArray,
    config: DanmuLayoutConfig,
): List<LaidDanmu> {
    val out = ArrayList<LaidDanmu>(group.size)
    group.sortedBy { it.atTimeMs }.forEach { item ->
        if (item.text.isBlank()) return@forEach
        val lane = freeAtMs.indexOfFirst { it <= item.atTimeMs }
        if (lane < 0) return@forEach
        val width = config.measureWidth(item.text)
        // 占用时长按真实文字宽度算：长弹幕占轨道更久，才不会追尾
        freeAtMs[lane] = item.atTimeMs +
            ((width + config.gapPx) / config.speedPxPerSec * 1000f).toLong()
        out += LaidDanmu(
            text = item.text,
            atTimeMs = item.atTimeMs,
            lane = lane,
            colorHex = item.colorHex,
        )
    }
    return out
}

/**
 * 找出某一帧应当绘制的弹幕区间下标。
 *
 * 整集弹幕是升序的，用二分定位窗口，避免每帧遍历几千条。
 */
internal fun visibleRange(items: List<LaidDanmu>, positionMs: Long, travelMs: Long): IntRange {
    if (items.isEmpty() || positionMs < 0) return IntRange.EMPTY
    val from = positionMs - travelMs
    val to = positionMs
    var start = items.binarySearchFirst { it.atTimeMs >= from }
    var end = items.binarySearchFirst { it.atTimeMs > to }
    if (start < 0) start = 0
    if (end > items.size) end = items.size
    return if (start >= end) IntRange.EMPTY else start until end
}

/** 第一个满足条件的位置；找不到返回 size。 */
private inline fun List<LaidDanmu>.binarySearchFirst(predicate: (LaidDanmu) -> Boolean): Int {
    var low = 0
    var high = size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (predicate(this[mid])) high = mid else low = mid + 1
    }
    return low
}
