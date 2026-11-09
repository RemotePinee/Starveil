package com.starveil.core.model

/**
 * 消息中心的一条内容。
 *
 * 公告与推送共用这一个模型：语义靠 [kind] 区分（列表里的图标与标签），
 * 弹窗样式靠 [template] 区分（纯文字 / 带首图 / 带小动画）。
 * 当初设计成一分为二的表，后来发现两者只是"同一条消息的两种皮肤"，
 * 合并后运营只在一个后台页面发，客户端也只有一个列表。
 */
data class AppMessage(
    val id: Int,
    val kind: MessageKind,
    val template: MessageTemplate,
    val title: String,
    val content: String,
    /** 卡片模板的背景图地址；空串表示不带图。 */
    val cover: String = "",
    /** 卡片模板药丸里的图标，后台下发的是 Emoji 字符。 */
    val icon: String = "",
    /** 卡片模板药丸文字；空串表示不画药丸。 */
    val badge: String = "",
    /** 主按钮文案，空串时按 [kind] 取默认值。 */
    val cta: String = "",
    val action: MessageAction = MessageAction.None,
    val createdAt: Long = 0,
    val read: Boolean = false,
    /**
     * 强制弹出：关不掉，必须点主按钮才消失。
     *
     * 只对弹窗生效，列表里照常可以划走——强制的是"这次启动必须看到"，
     * 不是"这条消息永久不可忽略"。
     */
    val force: Boolean = false,
)

/** 公告：通知、协议、维护说明这类"信息型"内容。 */
enum class MessageKind { ANNOUNCE, PUSH }

/**
 * 弹窗用哪套模板。
 *
 * 后端下发未知值时落到 [TEXT]：宁可退成纯文字，也不要在用户面前弹一个空白框。
 */
enum class MessageTemplate {
    TEXT,
    NOTICE,
    UPDATE,

    /**
     * 卡片式：整卡大图 + 左上角标 + 标题 + 正文 + 主按钮。
     *
     * 唯一走「宽版」版式的模板，其余三项都是窄条；也因此只有它读 [AppMessage.cover]。
     */
    CARD,
}

/**
 * 点击后的去向。
 *
 * 后端用 `action_type + action_value` 两个字段表达，这里收敛成一个密封类，
 * UI 侧只面对"去哪"，不再各自解析字符串。
 */
sealed interface MessageAction {
    data object None : MessageAction
    data class Vod(val id: Int) : MessageAction
    data class Type(val id: Int) : MessageAction
    data class Url(val url: String) : MessageAction
}

/** 一页消息 + 未读总数。 */
data class MessagePage(
    val items: List<AppMessage>,
    val total: Int,
    val unread: Int,
)
