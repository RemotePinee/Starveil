package com.starveil.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `v1.vod/types` 的响应体：`data.list` 是分类数组。 */
@Serializable
data class VodTypeListDto(
    @SerialName("list") val list: List<VodTypeDto> = emptyList(),
)

@Serializable
data class VodTypeDto(
    @SerialName("type_id") val id: Int = 0,
    @SerialName("type_name") val name: String = "",
    @SerialName("type_pid") val parentId: Int = 0,
    /**
     * 筛选维度。后端可能整块缺省、回 `{}`、或回 `null`，
     * 三种情况都用可空类型接住，映射时降级成空列表（见 [com.starveil.core.network.mapper.toDomain]）。
     */
    @SerialName("extend") val extend: VodTypeExtendDto? = null,
)

/**
 * `extend` 的载荷：`{"area":[...],"year":[...],"class":[...],"lang":[...]}`。
 *
 * 每个键都可缺省、也可能是空数组。DTO 只描述后端字段，不做清洗——
 * 去空串、去重放在 mapper。
 */
@Serializable
data class VodTypeExtendDto(
    @SerialName("area") val area: List<String>? = null,
    @SerialName("year") val year: List<String>? = null,
    @SerialName("class") val classNames: List<String>? = null,
    @SerialName("lang") val lang: List<String>? = null,
)
