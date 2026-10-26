package com.starveil.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HomeBannerListDto(@SerialName("list") val list: List<HomeBannerDto> = emptyList())

@Serializable
data class HomeBannerDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("title") val title: String = "",
    @SerialName("subtitle") val subtitle: String = "",
    @SerialName("pic") val pic: String = "",
    @SerialName("vod_id") val vodId: Int = 0,
    @SerialName("url") val url: String = "",
    @SerialName("style") val style: String = "",
)

@Serializable
data class HomeBlockListDto(@SerialName("list") val list: List<HomeBlockDto> = emptyList())

@Serializable
data class HomeBlockDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("title") val title: String = "",
    @SerialName("subtitle") val subtitle: String = "",
    @SerialName("layout") val layout: String = "row",
    @SerialName("vods") val vods: List<VodDto> = emptyList(),
)

@Serializable
data class HomeLinkListDto(@SerialName("list") val list: List<HomeLinkDto> = emptyList())

/**
 * 快捷入口：背景图 + 标题 + 副标题。
 *
 * `bg` 由后台填完整 URL 或上传后得到；后端仍下发 `icon` 时直接忽略。
 */
@Serializable
data class HomeLinkDto(
    @SerialName("title") val title: String = "",
    @SerialName("subtitle") val subtitle: String = "",
    @SerialName("bg") val bg: String = "",
    @SerialName("target_type") val targetType: String = "url",
    @SerialName("target_id") val targetId: Int = 0,
    @SerialName("url") val url: String = "",
)

/**
 * 首页单个卡片位（编辑精选 / 热度榜 / 猜你喜欢共用）。`vods` 与 [HomeBlockDto.vods]
 * 同构，未配置时后端下发空数组而不是 null，所以默认值给 emptyList()，
 * UI 据此判定是否回退到列表切片。
 *
 * 标题原样透传（含空串）：三个位置的回退文案不同，由 UI 按位置决定，
 * 映射层不替它选「编辑精选」。
 */
@Serializable
data class HomeSpotlightDto(
    @SerialName("title") val title: String = "",
    @SerialName("subtitle") val subtitle: String = "",
    @SerialName("vods") val vods: List<VodDto> = emptyList(),
)

/** 每日推荐（`v1.main/push`）：只下发条数，没有列表。 */
@Serializable
data class HomePushDto(@SerialName("count") val count: Int = 0)
