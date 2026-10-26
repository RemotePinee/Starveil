package com.starveil.core.model

/**
 * 收藏条目，来自 `GET v1.user/favs`。
 *
 * 取消收藏传的是影片 id（[vodId]），不是 [ulogId]：
 * 用 `data.id` 拼逗号串，再调 `DELETE v1.user/ulog?ids=`。
 *
 * 海报在 `pic` 与 `pic_thumb` 之间二选一、把相对路径补成绝对地址属于展示层，
 * 模型只保留原始值。
 */
data class FavoriteItem(
    val ulogId: Int,
    val moduleId: Int,
    val ulogType: Int,
    val vodId: Int,
    val sid: Int,
    val nid: Int,
    val points: Int,
    val createdAt: Long,
    val userName: String?,
    val title: String,
    val poster: String?,
    val link: String?,
    val categoryName: String?,
)
