package com.starveil.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * 榜单列表，来自 `v1.vod/vodphb`（常量 ）。
 *
 * 返回壳是 `BaseResult<RankBean>`，`data` 里**只有 list**，没有 total / page / limit
 * （只有一个字段），所以不能复用 [PageDto]：
 * 旧榜单页靠"本次返回条数"判断是否还有下一页。
 * 条目是完整 VodBean，直接复用 [VodDto]。
 */
@Serializable
data class RankBoardDto(
    @SerialName("list") val list: List<VodDto> = emptyList(),
)

/**
 * 全量榜单，来自 `v1.vod/vodPhbAll`。
 *
 * 两个字段都是「分类 -> 影片」的两层结构：
 * - `zhui`：旧首页取出来后只做了空转，没有单独展示；
 * - `list`：真正铺卡片的那组分类。
 *
 * 旧声明没有包 `PageResult`，`NewRecommendBean2` 自己写了 code/msg/data，
 * 所以这里用 [ApiEnvelopeDto] 承接，而不是分页壳。
 */
@Serializable
data class RankCatalogDto(
    @SerialName("zhui") val promoted: List<RankGroupDto> = emptyList(),
    @SerialName("list") val groups: List<RankGroupDto> = emptyList(),
)

/**
 * 榜单分类分组，
 *
 * 分类字段两套写法都要认：
 * - `vod_type_*`：后端 `RecommendBean2` 的写法，也是本客户端最初的契约；
 * - `type_*`：插件 `Repo\Vods::catalog()` 目前的写法。
 * 只认其中一套时，另一套会被解析成 `typeId = 0` + 空名字，界面上就是"没有文字的标签"。
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RankGroupDto(
    @SerialName("vod_type_id") @JsonNames("type_id") val typeId: Int = 0,
    @SerialName("vod_type_name") @JsonNames("type_name") val typeName: String = "",
    @SerialName("vod_type_img") @JsonNames("type_img") val typeImage: String? = null,
    @SerialName("vod_list") val vods: List<VodDto> = emptyList(),
)
