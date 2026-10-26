package com.starveil.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 官方弹幕（qilin-danmu）响应：`GET {解析站}/danmu?url=<原始视频地址>`。
 *
 * `items[].time` 是**秒**（float），与用户弹幕的毫秒口径不同，映射层负责换算。
 * 弹幕服务不可用时这里会回 `code=200, items=[]`，所以"空列表"不等于出错。
 */
@Serializable
data class OfficialDanmuListDto(
    @SerialName("code") val code: Int = 200,
    @SerialName("count") val count: Int = 0,
    @SerialName("source") val source: String = "",
    @SerialName("items") val items: List<OfficialDanmuItemDto> = emptyList(),
)

@Serializable
data class OfficialDanmuItemDto(
    @SerialName("time") val time: Double = 0.0,
    @SerialName("text") val text: String = "",
    /** 0 滚动 / 1 顶部 / 2 底部，客户端目前只当滚动处理。 */
    @SerialName("mode") val mode: Int = 0,
    @SerialName("color") val color: String? = null,
)
