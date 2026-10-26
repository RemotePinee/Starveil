package com.starveil.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 直投激励视频的三个接口（`v1.ad/session|progress|complete`）。
 *
 * 服务端按"进度 ≥ seconds 且真实经过时间 ≥ seconds-1"两条一起判，
 * 通过后**由服务端自己写解锁记录**；这三个响应都不带权限位，
 * 客户端拿不到"能看了"的结论，只能事后重查 `checkVodTrySee`。
 */
@Serializable
data class AdSessionDto(
    @SerialName("token") val token: String = "",
    /** 服务端认定的观看时长（秒），完成校验也按它。 */
    @SerialName("seconds") val seconds: Int = 0,
    /** 这一条广告实际要播的地址；后台换了素材不必发版。 */
    @SerialName("video_url") val videoUrl: String = "",
)

/** `v1.ad/progress` 的 `data`：只表示"这一笔进度记下了"，不解锁。 */
@Serializable
data class AdProgressDto(
    @SerialName("ok") val ok: Int = 0,
)

/** `v1.ad/complete` 的 `data`：`unlocked == 1` 才是服务端真写了本集解锁记录。 */
@Serializable
data class AdCompleteDto(
    @SerialName("unlocked") val unlocked: Int = 0,
)
