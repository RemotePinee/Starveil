package com.starveil.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 影片 DTO。字段名保持后端 snake_case，用 @SerialName 逐个显式标注，
 * 不依赖命名策略，避免后端换字段名时静默变成 null。
 *
 * 所有字段都有默认值：后端经常缺字段（例如 Vodad 少 sdkad、jiexi_key），
 * 缺字段必须能反序列化成功，由业务层决定降级。
 */
@Serializable
data class VodDto(
    @SerialName("vod_id") val id: Int = 0,
    @SerialName("vod_name") val name: String = "",
    @SerialName("vod_pic") val poster: String? = null,
    @SerialName("vod_remarks") val remarks: String? = null,
    @SerialName("vod_year") val year: String? = null,
    @SerialName("vod_area") val area: String? = null,
    @SerialName("vod_score") val score: String? = null,
    @SerialName("vod_blurb") val blurb: String? = null,
    /**
     * 后端 `type_id`，`VodBean.type_id` 的同名 int 字段。
     * 详情页用它拉「相关推荐」（旧 传 `mVodBean.type_id`）。
     */
    @SerialName("type_id") val typeId: Int = 0,
    /**
     * 后端新下发的分类名（`mac_type.type_name`），由插件按 type_id 批量查出来。
     * 后端没有这个字段，默认 null，UI 不得据 null 推断"无分类"以外的含义。
     */
    @SerialName("type_name") val typeName: String? = null,
    @SerialName("vod_play_list") val playSources: List<PlaySourceDto> = emptyList(),
)

@Serializable
data class PlaySourceDto(
    @SerialName("sid") val sid: Int = 0,
    @SerialName("from") val from: String = "",
    @SerialName("url_count") val urlCount: Int = 0,
    @SerialName("urls") val urls: List<PlayUrlDto> = emptyList(),
    @SerialName("player_info") val playerInfo: PlayerInfoDto = PlayerInfoDto(),
)

@Serializable
data class PlayUrlDto(
    @SerialName("nid") val nid: Int = 0,
    @SerialName("name") val name: String = "",
    @SerialName("url") val url: String = "",
)

/**
 * 后端只透传字符串，语义由客户端约定，详见 PlayerInfo 领域模型的注释。
 */
@Serializable
data class PlayerInfoDto(
    @SerialName("parse2") val parse2: String? = null,
    @SerialName("features") val features: String? = null,
    @SerialName("issethead") val issetHead: String? = null,
    @SerialName("headers") val headers: String? = null,
    // 红果 CENC 解密：0=关（默认） 1=开；match 是逗号分隔的识别规则
    @SerialName("hongguo") val hongguo: String? = "0",
    @SerialName("hongguo_match") val hongguoMatch: String? = null,
)
