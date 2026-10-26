package com.starveil.core.model

/**
 * 榜单分类分组，来自 `v1.vod/vodPhbAll`。
 *
 * [typeImage] 对应 `vod_type_img`，旧首页并不使用它，保留是为了不丢后端字段。
 */
data class RankGroup(
    val typeId: Int,
    val typeName: String,
    val typeImage: String?,
    val vods: List<Vod>,
)

/**
 * 全量榜单目录，来自 `v1.vod/vodPhbAll`。
 *
 * [promoted] 对应后端 `zhui`：旧首页取出来后只做了空转，没有单独展示；
 * [groups] 对应 `list`，才是真正铺卡片的分类列表
 * 两者语义不同，不要合并成一个列表。
 */
data class RankCatalog(
    val promoted: List<RankGroup>,
    val groups: List<RankGroup>,
)
