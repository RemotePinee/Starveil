package com.starveil.core.model

/**
 * 积分中心状态：余额 + 今日签到情况。
 *
 * 积分来源见后端：每日签到、观看满 30 分钟、分享（`UserController::getTask`）。
 * 积分只有两个去处：买断单集（`Payment::buyEpisode` 扣 `episode_points`）、后台手动加减。
 */
data class PointsStatus(
    val points: Int,
    val signedToday: Boolean,
    /** 今日签到可得积分，用于按钮文案（已签到时后端同样回这个数）。 */
    val signPoints: Int,
)

/** 一条积分任务。`done` = 今日做完且还能领，`claimed` = 已领过（或接口自动发过奖）。 */
data class PointsTask(
    val key: String,
    val title: String,
    val points: Int,
    val done: Boolean,
    /** 目标次数；后台"观看影片 3 次"这类任务要拿它算进度。 */
    val target: Int = 1,
    val progress: Int = 0,
    val claimed: Boolean = false,
)

/** 分享文案：后台 `v1.user/shareInfo` 下发，空串表示没配，由 UI 回落到应用名。 */
data class ShareLink(
    val url: String,
    val title: String,
    val description: String,
)

/**
 * 积分页首帧快照：余额/签到状态 + 今日任务。
 *
 * 这两份数据来自两个接口（`v1.user/sign`、`v1.user/task`），一次请求回不来；
 * 合成一份模型存一张快照，页面冷启动就能同时铺出余额与任务列表，不必等两个接口。
 */
data class PointsSnapshot(
    val status: PointsStatus,
    val tasks: List<PointsTask>,
)
