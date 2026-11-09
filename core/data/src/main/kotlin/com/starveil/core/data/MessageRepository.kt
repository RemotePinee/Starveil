package com.starveil.core.data

import com.starveil.core.common.AppResult
import com.starveil.core.model.AppMessage
import com.starveil.core.model.AppVersion
import com.starveil.core.model.MessageAction
import com.starveil.core.model.MessageKind
import com.starveil.core.model.MessagePage
import com.starveil.core.model.MessageTemplate
import com.starveil.core.network.MessageApi
import com.starveil.core.network.dto.MessageDto
import com.starveil.core.network.dto.MessageListDto
import com.starveil.core.network.dto.MessageReadDto
import com.starveil.core.network.dto.VersionDto

/**
 * 消息中心：公告与推送共用。
 *
 * 接口方法的默认实现让其它模块的测试 fake 不用跟着改（与 `VodRepository` 同一约定）：
 * 新加方法时先在接口里给个"空成功"，需要真实数据的实现类再覆盖。
 */
interface MessageRepository {

    /** 拉一页消息；[page] 从 1 开始。 */
    suspend fun list(page: Int = 1, size: Int = MessageApi.DEFAULT_PAGE_SIZE): AppResult<MessagePage> =
        AppResult.Success(MessagePage(emptyList(), 0, 0))

    /** 批量标记已读。 */
    suspend fun markRead(ids: List<Int>): AppResult<Unit> = AppResult.Success(Unit)

    /** 只有未读数：铃铛红点用，不需要拉整页。 */
    suspend fun unreadCount(): AppResult<Int> = AppResult.Success(0)
}

class DefaultMessageRepository(private val api: MessageApi) : MessageRepository {

    override suspend fun list(page: Int, size: Int): AppResult<MessagePage> =
        apiCall { api.messages(page, size) }
            .requireData()
            .flatMap { AppResult.Success(it.toDomain()) }

    override suspend fun markRead(ids: List<Int>): AppResult<Unit> {
        if (ids.isEmpty()) return AppResult.Success(Unit)
        return apiCall { api.markRead(MessageReadDto(ids)) }.requireSuccess()
    }

    override suspend fun unreadCount(): AppResult<Int> =
        list(page = 1, size = 1).flatMap { AppResult.Success(it.unread) }
}

/** 在线更新仓库：只负责"拿到后端说的最新版本"。下载与安装不在这里（要 Context）。 */
interface UpdateRepository {
    suspend fun latest(): AppResult<AppVersion> = AppResult.Success(AppVersion())
}

class DefaultUpdateRepository(private val api: MessageApi) : UpdateRepository {
    override suspend fun latest(): AppResult<AppVersion> =
        apiCall { api.version() }.requireData().flatMap { AppResult.Success(it.toDomain()) }
}

private fun MessageListDto.toDomain(): MessagePage = MessagePage(
    items = list.map(MessageDto::toDomain),
    total = total,
    unread = unread,
)

/**
 * DTO → 领域模型。
 *
 * 后端把 `kind` 存成 tinyint、下发成字符串；这里对未知值一律保守降级：
 * 未知 kind 当公告（不进"活动"标签）、未知 template 当纯文字（不弹空框）。
 */
internal fun MessageDto.toDomain(): AppMessage = AppMessage(
    id = id,
    kind = if (kind.equals(KIND_PUSH, ignoreCase = true)) MessageKind.PUSH else MessageKind.ANNOUNCE,
    template = when (template.lowercase()) {
        TEMPLATE_NOTICE -> MessageTemplate.NOTICE
        TEMPLATE_UPDATE -> MessageTemplate.UPDATE
        TEMPLATE_CARD -> MessageTemplate.CARD
        else -> MessageTemplate.TEXT
    },
    title = title,
    content = content,
    cover = cover,
    icon = icon,
    badge = badge,
    cta = cta,
    action = toAction(),
    createdAt = createdAt,
    read = read,
    force = force,
)

private fun MessageDto.toAction(): MessageAction = when (actionType.lowercase()) {
    ACTION_VOD -> actionValue.toIntOrNull()?.let(MessageAction::Vod) ?: MessageAction.None
    ACTION_TYPE -> actionValue.toIntOrNull()?.let(MessageAction::Type) ?: MessageAction.None
    ACTION_URL -> actionValue.takeIf(String::isNotBlank)?.let(MessageAction::Url) ?: MessageAction.None
    else -> MessageAction.None
}

internal fun VersionDto.toDomain(): AppVersion = AppVersion(
    code = code,
    name = name,
    url = url,
    sha256 = sha256,
    force = force,
    changelog = changelog,
)

private const val KIND_PUSH = "push"
private const val TEMPLATE_NOTICE = "notice"
private const val TEMPLATE_UPDATE = "update"
private const val TEMPLATE_CARD = "card"
private const val ACTION_VOD = "vod"
private const val ACTION_TYPE = "type"
private const val ACTION_URL = "url"
