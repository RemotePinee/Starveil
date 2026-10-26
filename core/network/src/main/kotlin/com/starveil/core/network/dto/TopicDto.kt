package com.starveil.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 专题列表条目，来自 `v1.topic/topicList`（
 *
 * `topic_id` 在后端 bean 里就是 String，旧页面把它塞进 Intent 传给详情页
 * 保持字符串，不偷偷转 Int。
 */
@Serializable
data class TopicDto(
    @SerialName("topic_id") val id: String = "",
    @SerialName("topic_name") val name: String = "",
    @SerialName("topic_pic") val poster: String? = null,
    @SerialName("topic_blurb") val blurb: String? = null,
    @SerialName("topic_content") val content: String? = null,
)

/**
 * 专题详情，来自 `v1.topic/topicDetail`（
 *
 * 与列表的差别只有 `topic_tag` 和 `vod_list`：详情把影片直接内嵌成数组，
 * 没有分页——后端 bean 里的 `page` 字段被注释掉了，后端不认分页参数。
 * 旧详情页展示前会手动去掉 `topic_content` 里的 `<p>`，
 * 那是展示层的事，DTO 保留原始 HTML。
 */
@Serializable
data class TopicDetailDto(
    @SerialName("topic_name") val name: String = "",
    @SerialName("topic_pic") val poster: String? = null,
    @SerialName("topic_blurb") val blurb: String? = null,
    @SerialName("topic_content") val content: String? = null,
    @SerialName("topic_tag") val tag: String? = null,
    @SerialName("vod_list") val vods: List<VodDto> = emptyList(),
)
