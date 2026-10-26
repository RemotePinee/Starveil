package com.starveil.core.network.mapper

import com.starveil.core.model.UserProfile
import com.starveil.core.network.dto.UserInfoDto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * DTO -> 领域模型，必须显式映射（AGENTS.md 第 4 节：禁止 DTO 进入 UI）。
 *
 * `group` 可空：游客态后端不下发会员组，此时 [UserProfile.groupName] 为空串，
 * 由 UI 决定显示"未开通"还是隐藏整块。
 */
fun UserInfoDto.toDomain(): UserProfile = UserProfile(
    userId = userId,
    userName = userName,
    nickName = nickName.decodeIfEncoded(),
    portrait = portrait,
    groupId = groupId,
    groupName = (group?.groupName.orEmpty()).decodeIfEncoded(),
    level = level,
    points = points,
    userPoints = userPoints,
    gold = gold,
    status = status,
    memberEndTime = endTime,
    viewCount = viewCount,
    viewSeconds = viewSeconds,
    isVip = isVip.truthy(),
    isAgents = isAgents,
)

/**
 * 昵称是 urlencode 之后存的（苹果CMS 前台注册走 `urlencode`），直接展示会看到
 * `%E6%B8%B8%E5%AE%A2` 这种串。没有 `%` 就原样返回，避免把 `+` 误转成空格。
 */
private fun String.decodeIfEncoded(): String =
    if (!contains('%')) this else runCatching { java.net.URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)

/** `is_vip` 兼容布尔与 0/1 两种写法。 */
private fun JsonElement?.truthy(): Boolean = when (this?.jsonPrimitive?.contentOrNull?.lowercase()) {
    "true", "1" -> true
    else -> false
}
