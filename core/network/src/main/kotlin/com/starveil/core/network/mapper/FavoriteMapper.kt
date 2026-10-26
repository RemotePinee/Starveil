package com.starveil.core.network.mapper

import com.starveil.core.model.FavoriteItem
import com.starveil.core.network.dto.FavoriteDto

/**
 * DTO -> 领域模型。
 *
 * 后端有两代格式，取值顺序就是兼容策略：
 * 1. 插件扁平格式优先：`vod_id` / `title` / `poster` / `created_at`；
 * 2. 旧 macCMS 嵌套格式退回：`data.id` / `data.name` / `data.pic` / `ulog_time`；
 * 3. 影片被删时 `data` 缺失，影片 id 退回 `ulog_rid`。
 *
 * 之前只认旧格式的后果：标题空 → 收藏页显示"影片已下架"；`vod_id` 全为 0 →
 * 所有条目共用一个 id，取消一条看着像取消了一整列。
 */
fun FavoriteDto.toDomain(): FavoriteItem = FavoriteItem(
    ulogId = ulogId,
    moduleId = ulogMid,
    ulogType = ulogType,
    vodId = firstNonZero(vodId, data?.id ?: 0, ulogRid),
    sid = ulogSid,
    nid = ulogNid,
    points = ulogPoints,
    createdAt = firstNonZero(createdAt, ulogTime.toLong()),
    userName = userName,
    title = title.ifBlank { data?.name.orEmpty() },
    poster = poster?.takeIf { it.isNotBlank() }
        ?: data?.picThumb?.takeIf { it.isNotBlank() }
        ?: data?.pic,
    link = data?.link,
    categoryName = data?.type?.typeName,
)

/** 取第一个非 0 值：0 在这两套契约里都表示"没给"。 */
private fun firstNonZero(vararg values: Long): Long = values.firstOrNull { it != 0L } ?: 0L

private fun firstNonZero(vararg values: Int): Int = values.firstOrNull { it != 0 } ?: 0
