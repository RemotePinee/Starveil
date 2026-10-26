package com.starveil.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 直播分类：`group` 为空串表示「全部」，`count` 是该分类下的频道数。 */
@Serializable
data class LiveGroupDto(
    @SerialName("group") val group: String = "",
    @SerialName("count") val count: Int = 0,
)

/**
 * 直播列表的一页。
 *
 * 不用通用 `PageDto`：`v1.zhibo` 额外下发 `groups`（分类 + 每组数量），
 * 客户端左侧分类栏直接用它，不必为每个分类再发一次请求。
 */
@Serializable
data class LivePageDto(
    @SerialName("total") val total: Int = 0,
    @SerialName("page") val page: Int = 1,
    @SerialName("limit") val limit: Int = 20,
    @SerialName("groups") val groups: List<LiveGroupDto> = emptyList(),
    @SerialName("list") val list: List<LiveDto> = emptyList(),
)
