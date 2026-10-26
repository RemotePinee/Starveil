package com.starveil.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/**
 * 解析站 `/json?url=...` 的响应。
 *
 * 所有字段都可空：不同解析站的返回差异很大，缺字段时由
 * `ParseRules.normalizeStream` 统一收敛，不在这里抛异常。
 *
 * B 站双流的关键字段是 [audio] 与 [type]（`type = "dash"`，但没有 `.mpd` 清单）。
 * [levels] 原样保留，当前不消费——清晰度菜单还没做，先不引入半成品数据。
 */
@Serializable
data class ParseResultDto(
    @SerialName("code") val code: JsonPrimitive? = null,
    @SerialName("msg") val msg: String? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("audio") val audio: String? = null,
    /** 官方弹幕地址（解析站 `/json` 的 `danmu`，指向 qilin-danmu）。 */
    @SerialName("danmu") val danmu: String? = null,
    /** 播放该流必须携带的 Referer（B站/CDN 防盗链）。 */
    @SerialName("referer") val referer: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("quality") val quality: String? = null,
    @SerialName("width") val width: Int? = null,
    @SerialName("height") val height: Int? = null,
)
