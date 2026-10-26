package com.starveil.core.network.mapper

import com.starveil.core.model.Topic
import com.starveil.core.model.TopicDetail
import com.starveil.core.network.dto.TopicDetailDto
import com.starveil.core.network.dto.TopicDto

/**
 * 专题 DTO -> 领域模型。
 *
 * 详情里的影片是完整 VodBean，直接复用 `VodDto.toDomain`，不再包一层专题专用影片模型。
 * `topic_content` 的 HTML 不在这一层处理：旧详情页是在 `setText` 前手动
 * `replace("<p>", "")`，属于展示层。
 */
fun TopicDto.toDomain(): Topic = Topic(
    id = id,
    name = name,
    poster = poster,
    blurb = blurb,
    content = content,
)

fun TopicDetailDto.toDomain(): TopicDetail = TopicDetail(
    name = name,
    poster = poster,
    blurb = blurb,
    content = content,
    tag = tag,
    vods = vods.map { it.toDomain() },
)
