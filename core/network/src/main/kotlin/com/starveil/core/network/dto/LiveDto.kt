package com.starveil.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 直播条目，来自 `v1.zhibo`。
 *
 * 只有四个字段，没有分类、没有分页参数：旧直播页拿到的就是一整条列表
 * 播放地址直接放在 `url` 里，不走线路解析。
 * `id` 在后端 bean 里是 `long`，频道号可能超过 Int，别改成 Int。
 */
@Serializable
data class LiveDto(
    @SerialName("id") val id: Long = 0,
    @SerialName("name") val name: String = "",
    @SerialName("img") val img: String? = null,
    @SerialName("url") val url: String? = null,
    /** 分组名（M3U 的 group-title）。空串由服务端归成「未分组」，这里保持原样。 */
    @SerialName("group") val group: String = "",
)
