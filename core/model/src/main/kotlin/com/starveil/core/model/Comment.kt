package com.starveil.core.model

/**
 * 影片评论，来自 `GET/POST v1.comment`
 * （服务端 `server/starveilapi/src/Controller/CommentController.php`）。
 *
 * 列表按 `comment_id desc` 返回，只有 `status = 1`（已通过审核）的评论可见，
 * 所以服务端过滤掉的内容客户端拿不到、也不需要判断。
 *
 * [createdAt] 是秒级时间戳，与弹幕的 `danmu_time` 同口径，不是毫秒。
 * [portrait] 可能是空串（游客或没设头像），也可能是站内相对路径；
 * 渲染层只认 `https://` 开头的绝对地址，其余走首字母占位。
 */
data class Comment(
    val id: Int,
    val content: String,
    val nickName: String,
    val portrait: String = "",
    val createdAt: Long = 0,
)

/**
 * 评论分页。
 *
 * [total] 是「本片全部可见评论数」，不是当前页条数：详情页的「查看全部 N 条」用它。
 * 后端把 `limit` 夹在 1..60，超出会被静默截断，因此不能用 `items.size` 反推总数。
 */
data class CommentPage(
    val items: List<Comment> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val limit: Int = 20,
)
