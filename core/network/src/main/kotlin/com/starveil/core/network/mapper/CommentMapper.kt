package com.starveil.core.network.mapper

import com.starveil.core.model.Comment
import com.starveil.core.model.CommentPage
import com.starveil.core.network.dto.CommentDto
import com.starveil.core.network.dto.CommentListDto

/**
 * DTO -> 领域模型。
 *
 * 昵称空串统一落成「游客」：后端 join 不到用户时给的是 `"游客"`，
 * 但字段缺失（老数据）会是空串，两种都要能显示。
 */
fun CommentDto.toDomain(): Comment = Comment(
    id = id,
    content = content,
    nickName = nickName.ifBlank { GUEST_NICK_NAME },
    portrait = portrait,
    createdAt = createdAt,
)

/** 列表壳 -> 分页模型。`total` 原样透传，UI 的「查看全部 N 条」依赖它。 */
fun CommentListDto.toDomain(): CommentPage = CommentPage(
    items = list.map { it.toDomain() },
    total = total,
    page = page,
    limit = limit,
)

private const val GUEST_NICK_NAME = "游客"
