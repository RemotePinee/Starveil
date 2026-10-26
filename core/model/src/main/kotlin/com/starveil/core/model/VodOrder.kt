package com.starveil.core.model

/**
 * `v1.vod` 的 `order` 白名单（片库筛选页的「排序」行）。
 *
 * 后端不校验非法值时会静默退回默认顺序，看起来像"筛了没反应"；
 * 所以下发前统一过 [sanitize]，业务代码只引用这里的常量，不写字面量。
 */
object VodOrder {

    const val TIME = "time"
    const val HITS = "hits"
    const val SCORE = "score"
    const val HITS_MONTH = "hits_month"

    /** 缺省排序：最新。后端不传 `order` 时也是这个顺序。 */
    const val DEFAULT = TIME

    val ALL: List<String> = listOf(TIME, HITS, SCORE, HITS_MONTH)

    /** 空串（「全部」）或白名单外的值一律落回 [DEFAULT]。 */
    fun sanitize(value: String): String = if (value in ALL) value else DEFAULT
}
