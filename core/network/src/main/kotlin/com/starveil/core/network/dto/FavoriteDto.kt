package com.starveil.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 收藏条目 DTO。
 *
 * 后端有两代返回格式，都要认：
 * 1. 插件当前格式（扁平）：`{ulog_id, vod_id, title, poster, created_at}`；
 * 2. 旧 macCMS 格式（嵌套）：`data` 里放影片摘要，时间字段叫 `ulog_time`。
 *
 * 只按旧格式解析时，标题、海报、时间全是空/0 —— 收藏页就会显示"影片已下架"，
 * 而 `vod_id` 落成 0 会让所有条目共用同一个 id，取消一条看着像取消了一整列。
 */
@Serializable
data class FavoriteDto(
    @SerialName("ulog_id") val ulogId: Int = 0,
    @SerialName("user_id") val userId: Int = 0,
    @SerialName("ulog_mid") val ulogMid: Int = 0,
    @SerialName("ulog_type") val ulogType: Int = 0,
    @SerialName("ulog_rid") val ulogRid: Int = 0,
    @SerialName("ulog_sid") val ulogSid: Int = 0,
    @SerialName("ulog_nid") val ulogNid: Int = 0,
    @SerialName("ulog_points") val ulogPoints: Int = 0,
    @SerialName("ulog_time") val ulogTime: Int = 0,
    @SerialName("user_name") val userName: String? = null,
    // 插件扁平格式
    @SerialName("vod_id") val vodId: Int = 0,
    @SerialName("title") val title: String = "",
    @SerialName("poster") val poster: String? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("data") val data: FavoriteVodDto? = null,
)

/** 收藏里嵌套的影片摘要。海报有三个候选字段，归一化在展示层做。 */
@Serializable
data class FavoriteVodDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("name") val name: String = "",
    @SerialName("vod_content") val content: String? = null,
    @SerialName("pic") val pic: String? = null,
    @SerialName("pic_thumb") val picThumb: String? = null,
    @SerialName("link") val link: String? = null,
    @SerialName("type") val type: FavoriteTypeDto? = null,
)

/** 分类摘要，只有 id、名字和站内链接。 */
@Serializable
data class FavoriteTypeDto(
    @SerialName("type_id") val typeId: Int = 0,
    @SerialName("type_name") val typeName: String = "",
    @SerialName("link") val link: String? = null,
)
