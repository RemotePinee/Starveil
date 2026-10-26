package com.starveil.core.model

/**
 * 直投激励视频的一次观看会话（`v1.ad/session`）。
 *
 * [token] 是服务端签发的观看凭据：进度上报与完成都只认它，客户端既不拼接也不改时长。
 * [seconds] 是服务端认定的"看满多少秒"，`v1.ad/complete` 会按它校验，
 * 页面只用它判断"能不能点继续"，不是解锁依据。
 *
 * 客户端不落任何本地解锁标记：播完调 complete，再重查 `checkVodTrySee` 拿服务端结论。
 */
data class AdSession(
    val token: String,
    val seconds: Int,
    val videoUrl: String,
)
