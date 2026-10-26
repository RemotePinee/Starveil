package com.starveil.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement

/**
 * 用户详情 DTO，字段名与后端真实返回一致，
 * 接口见 ）。逐个显式 @SerialName，不依赖命名策略，
 * 后端换字段名时不会静默变成 null。
 *
 * 全部字段带默认值：游客态 / 未登录时后端会缺大量字段，
 * 缺字段必须能反序列化成功；多出来的未知字段由 [com.starveil.core.network.ApiClient]
 * 的 ignoreUnknownKeys 丢弃。
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UserInfoDto(
    @SerialName("user_id") val userId: Int = 0,
    @SerialName("user_name") val userName: String = "",
    @SerialName("user_nick_name") val nickName: String = "",
    @SerialName("user_portrait") val portrait: String? = null,
    @SerialName("user_portrait_thumb") val portraitThumb: String? = null,
    @SerialName("group_id") val groupId: Int = 0,
    @SerialName("user_level") val level: String? = null,
    @SerialName("points") val points: Int = 0,
    @SerialName("user_points") val userPoints: Int = 0,
    @SerialName("user_points_froze") val pointsFrozen: Int = 0,
    // 只认 `user_gold`：后端会同时返回 `gold` 与 `user_gold`，用 @JsonNames 别名
    // 会让同一属性在同一个 JSON 里出现两次，kotlinx 直接抛重复键 -> "数据解析失败"
    @SerialName("user_gold") val gold: String = "0",
    @SerialName("view_seconds") val viewSeconds: Long = 0,
    // 后端 `Playback::isVip` 的结论：group_id 命中会员组且未过期。
    // 服务端有的版本回 true/false，有的回 0/1，用 JsonElement 接，映射层归一成 Boolean。
    // 写死成 Int 时遇到布尔会整包解析失败（页面表现为"数据解析失败"）。
    @SerialName("is_vip") val isVip: JsonElement? = null,
    @SerialName("user_status") val status: Int = 0,
    @SerialName("user_end_time") val endTime: Long = 0,
    @SerialName("user_view_count") val viewCount: String? = null,
    @SerialName("is_agents") val isAgents: String? = null,
    @SerialName("group") val group: UserGroupDto? = null,
)

/**
 * 会员组。`group_type` 是形如 `,1,6,7,` 的逗号串，权限判定归领域层，
 * 保持原串不解析（旧 `UserInfoBean.GroupBean` 同样只透传）。
 */
@Serializable
data class UserGroupDto(
    @SerialName("group_id") val groupId: Int = 0,
    @SerialName("group_name") val groupName: String = "",
    @SerialName("group_status") val status: Int = 0,
    @SerialName("group_type") val type: String = "",
    @SerialName("group_points_day") val pointsPerDay: Int = 0,
    @SerialName("group_points_week") val pointsPerWeek: Int = 0,
    @SerialName("group_points_month") val pointsPerMonth: Int = 0,
    @SerialName("group_points_year") val pointsPerYear: Int = 0,
    @SerialName("group_points_free") val pointsFree: Int = 0,
)
