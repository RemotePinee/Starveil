package com.starveil.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET v1.user/videoProgressList` 的 data 壳。 */
@Serializable
data class VideoProgressListDto(
    @SerialName("list") val list: List<VideoProgressDto> = emptyList(),
)

/**
 * 单条播放进度。
 *
 * `source_index` / `episode_index` 是 0 起下标，服务端按 `cuican_vlog` 原样下发；
 * `vod_name` / `vod_pic` 来自 join `mac_vod`，影片不存在（下架）时服务端已跳过。
 */
@Serializable
data class VideoProgressDto(
    @SerialName("vod_id") val vodId: Int = 0,
    @SerialName("vod_name") val vodName: String = "",
    @SerialName("vod_pic") val vodPic: String? = null,
    /** 类型名（`mac_type.type_name`）：客户端据此判断是不是短剧。 */
    @SerialName("type_name") val typeName: String? = null,
    /** 分类串（`mac_vod.vod_class`），type_name 缺失时的兜底。 */
    @SerialName("vod_class") val vodClass: String? = null,
    @SerialName("source_index") val sourceIndex: Int = 0,
    @SerialName("episode_index") val episodeIndex: Int = 0,
    @SerialName("progress_sec") val progressSec: Int = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
)
