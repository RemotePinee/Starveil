package com.starveil.core.model

/**
 * 专题条目，来自 `v1.topic/topicList`（
 *
 * [id] 是字符串：旧页面把它原样塞进 Intent，详情页再原样回传
 * （ → ）。
 */
data class Topic(
    val id: String,
    val name: String,
    val poster: String?,
    val blurb: String?,
    val content: String?,
)

/**
 * 专题详情，来自 `v1.topic/topicDetail`。
 *
 * [vods] 是内嵌的影片数组，不分页；[content] 保留后端下发的原始 HTML。
 */
data class TopicDetail(
    val name: String,
    val poster: String?,
    val blurb: String?,
    val content: String?,
    val tag: String?,
    val vods: List<Vod>,
)
