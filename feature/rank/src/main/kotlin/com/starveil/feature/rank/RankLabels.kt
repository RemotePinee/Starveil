package com.starveil.feature.rank

import com.starveil.core.model.Vod

/**
 * 分段控件下方的口径说明。只描述排序依据，不编造"每小时更新"这类无法验证的频率。
 */
internal fun periodHint(order: RankOrder): String = when (order) {
    RankOrder.DAY -> "按今日播放热度排序"
    RankOrder.WEEK -> "按本周播放热度排序"
    RankOrder.MONTH -> "按本月播放热度排序"
    RankOrder.ALL -> "按累计播放热度排序"
}

/**
 * 热度文案。
 *
 * `Vod` 没有独立的热度数值字段，后端能给的只有备注（"更新至 xx 集"）与评分，
 * 因此这里按"备注 · 评分"组合，缺哪个就少哪个，不用假数字凑。
 */
internal fun vodHeatText(vod: Vod): String {
    val remarks = vod.remarks.orEmpty().trim()
    val score = vod.score.orEmpty().trim()
    return when {
        remarks.isNotEmpty() && score.isNotEmpty() -> "$remarks · 评分 $score"
        remarks.isNotEmpty() -> remarks
        score.isNotEmpty() -> "评分 $score"
        else -> "暂无热度数据"
    }
}

/** 地区与年份，缺项自动省略，避免出现"· 2026"这种断头文案。 */
internal fun vodDetailText(vod: Vod): String = listOf(
    vod.area.orEmpty().trim(),
    vod.year.orEmpty().trim(),
).filter { it.isNotEmpty() }.joinToString(" · ")
