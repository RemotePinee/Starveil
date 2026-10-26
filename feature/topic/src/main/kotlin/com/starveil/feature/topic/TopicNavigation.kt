package com.starveil.feature.topic

import android.net.Uri

/**
 * 专题相关路由，供 `:app` 的 NavHost 汇总注册（AGENTS.md 第 8 节）。
 *
 * 是 Fragment + Activity 直接 Intent 跳转：
 * 把 topicid 塞进 Intent，
 * 再取出来。新实现换成导航目的地。
 */

/** 专题列表。 */
const val TOPIC_LIST_ROUTE = "topic"

/** 专题 id（字符串，后端 `toppic_id` 原样透传）。 */
const val ARG_TOPIC_ID = "topicId"

/** 专题详情。 */
const val TOPIC_DETAIL_ROUTE = "topic_detail"

/** NavHost 注册的模式串。 */
const val TOPIC_DETAIL_ROUTE_PATTERN = "$TOPIC_DETAIL_ROUTE/{$ARG_TOPIC_ID}"

/**
 * 生成详情跳转地址。
 * id 做一次编码：后端下发的是字符串，不能假定只含数字和短横线。
 */
fun topicDetailRoute(topicId: String): String = "$TOPIC_DETAIL_ROUTE/${Uri.encode(topicId)}"
