package com.starveil.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 行为类接口的返回值壳，只装一个积分字段。
 *
 * `score` 是字符串，旧的消费方式是 `data.score != "0"` 才提示"获得 N 积分"
 * 所以这里保持 String，不转数字：
 * 后端空返回时必须落回 "0" 而不是让调用方 NPE。
 */
@Serializable
data class ScoreDto(
    @SerialName("score") val score: String = "0",
)
