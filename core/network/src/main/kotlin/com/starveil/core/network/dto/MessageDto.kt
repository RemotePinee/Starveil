package com.starveil.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 消息中心列表：`v1.main/messages`。
 *
 * 公告与推送共用一条消息，靠 [MessageDto.kind] 区分语义、[MessageDto.template] 区分弹窗样式。
 * 这样运营只需要在一个页面里发，客户端也只需要一个列表页。
 */
@Serializable
data class MessageListDto(
    @SerialName("list") val list: List<MessageDto> = emptyList(),
    /** 总条数：分页用。 */
    @SerialName("total") val total: Int = 0,
    /** 当前用户的未读条数：铃铛红点直接用这个，不再自己数。 */
    @SerialName("unread") val unread: Int = 0,
)

/**
 * 一条公告 / 推送。
 *
 * 字段全部带默认值：老后端没有这些列时必须能反序列化成功，由 UI 降级成纯文字弹窗。
 */
@Serializable
data class MessageDto(
    @SerialName("id") val id: Int = 0,
    /** `announce` 公告 / `push` 推送。决定列表里的图标与标签，不影响正文。 */
    @SerialName("kind") val kind: String = "",
    /** `text` 纯文字 / `notice` / `update` / `card`（整卡大图）。决定弹窗用哪套模板。 */
    @SerialName("template") val template: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("content") val content: String = "",
    /** 卡片模板的背景图（绝对 URL）；空串表示不带图，退回纯文字排版。 */
    @SerialName("cover") val cover: String = "",
    /** 卡片模板药丸里的图标，后台下发的是 Emoji 字符。 */
    @SerialName("icon") val icon: String = "",
    /** 卡片模板左上角药丸文字；空串表示不画药丸。 */
    @SerialName("badge") val badge: String = "",
    /** 主按钮文案，空串按 kind 取默认值（公告「我知道了」/ 推送「立即查看」）。 */
    @SerialName("cta") val cta: String = "",
    /** `none` / `vod` / `type` / `url`，点击后跳到哪。 */
    @SerialName("action_type") val actionType: String = "",
    /** 跳转参数：vod/type 是 id，url 是地址。 */
    @SerialName("action_value") val actionValue: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    /** 当前用户是否已读；未登录时恒为 false。 */
    @SerialName("read") val read: Boolean = false,
    /**
     * 强制弹出：后台「强制弹出」开关，弹窗关不掉。
     *
     * 与 `VersionDto.force` 同名不同义：那边是"必须更新"，这边是"必须看一眼"。
     */
    @SerialName("force") val force: Boolean = false,
)

/** 批量标记已读的请求体：`POST v1.main/messageRead`。 */
@Serializable
data class MessageReadDto(
    @SerialName("ids") val ids: List<Int> = emptyList(),
)

/**
 * 在线更新：`v1.main/version`。
 *
 * 缺字段时 [code] 为 0，客户端据此判断"后端没配版本信息"，直接跳过检查。
 */
@Serializable
data class VersionDto(
    /** 与 `BuildConfig.VERSION_CODE` 比较；大于当前值才提示更新。 */
    @SerialName("code") val code: Int = 0,
    @SerialName("name") val name: String = "",
    /** APK 下载地址，只接受 https。 */
    @SerialName("url") val url: String = "",
    /** 安装前校验；空串表示后端没提供，跳过校验但会打日志。 */
    @SerialName("sha256") val sha256: String = "",
    /** 强制更新：弹窗不可关，只能下载。 */
    @SerialName("force") val force: Boolean = false,
    @SerialName("changelog") val changelog: String = "",
)
