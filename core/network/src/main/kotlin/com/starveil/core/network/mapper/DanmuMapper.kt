package com.starveil.core.network.mapper

import com.starveil.core.model.Danmu
import com.starveil.core.network.dto.OfficialDanmuListDto

/**
 * 官方弹幕（解析站 `danmu` 地址，qilin-danmu）→ 领域模型。
 *
 * 秒转毫秒，并用下标补 id（服务端不给 id）：[Danmu.id] 只用于去重键。
 * 本站用户弹幕走苹果CMS 的 danmaku 接口，映射在 core:data 里。
 */
fun OfficialDanmuListDto.toDomain(): List<Danmu> = items.mapIndexed { index, item ->
    Danmu(
        id = index + 1,
        content = item.text,
        userId = 0,
        atTimeMs = (item.time * 1000).toLong().coerceAtLeast(0L),
        createdAt = 0,
        status = 1,
        likeCount = 0,
        color = item.color,
    )
}
