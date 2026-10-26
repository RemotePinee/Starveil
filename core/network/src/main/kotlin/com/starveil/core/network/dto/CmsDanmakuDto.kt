package com.starveil.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * CMS 弹幕接口（`/api.php/danmaku/...`）。
 *
 * 这是苹果CMS 内置的弹幕库，网页播放器与后台「弹幕管理」读写的是同一张表，
 * 所以 App 直接用这套接口，不再自建弹幕表。
 *
 * 注意它的成功码是 **`code == 1`**（不是 200），与 `v1.*` 那套 `{code,msg,data}` 完全不同。
 */
@Serializable
data class CmsDanmakuListDto(
    @SerialName("code") val code: Int = 0,
    @SerialName("msg") val msg: String = "",
    @SerialName("info") val info: CmsDanmakuInfoDto? = null,
)

@Serializable
data class CmsDanmakuInfoDto(
    @SerialName("total") val total: Int = 0,
    @SerialName("rows") val rows: List<CmsDanmakuRowDto> = emptyList(),
)

@Serializable
data class CmsDanmakuRowDto(
    @SerialName("danmaku_id") val id: Int = 0,
    /** 秒（浮点），与客户端毫秒口径差 1000。 */
    @SerialName("danmaku_time") val time: Double = 0.0,
    @SerialName("danmaku_type") val type: Int = 0,
    @SerialName("danmaku_color") val color: String? = null,
    @SerialName("danmaku_text") val text: String = "",
    @SerialName("user_name") val userName: String = "",
    @SerialName("danmaku_send_time") val sendTime: Long = 0,
)

/** 发送结果：`code == 1` 成功，`msg` 是失败原因。 */
@Serializable
data class CmsDanmakuSendDto(
    @SerialName("code") val code: Int = 0,
    @SerialName("msg") val msg: String = "",
)
