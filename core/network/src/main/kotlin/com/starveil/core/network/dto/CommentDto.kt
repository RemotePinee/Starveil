package com.starveil.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET v1.comment` 的响应体：`data.{total,page,limit,list}`。
 *
 * 字段名与后端一致（`CommentController::getIndex`）。三个分页字段都给默认值：
 * 后端以后不再回 `total` 时列表仍能解析，UI 退化成「总数未知」而不是整页失败。
 */
@Serializable
data class CommentListDto(
    @SerialName("total") val total: Int = 0,
    @SerialName("page") val page: Int = 1,
    @SerialName("limit") val limit: Int = 20,
    @SerialName("list") val list: List<CommentDto> = emptyList(),
)

/**
 * 单条评论。`user_nick_name` / `user_portrait` 是后端 join 用户表后的展示字段，
 * 不是 `user` 对象；游客或已注销用户会落成 `"游客"` 与空串。
 *
 * `created_at` 是秒级时间戳（`CommentController::getIndex` 直接透传表字段），
 * 与弹幕的 `danmu_time` 同口径，不要当毫秒做除法。
 */
@Serializable
data class CommentDto(
    @SerialName("comment_id") val id: Int = 0,
    @SerialName("content") val content: String = "",
    @SerialName("user_nick_name") val nickName: String = "",
    @SerialName("user_portrait") val portrait: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
)
