package com.starveil.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 签到状态 `GET v1.user/sign`：余额、今天签没签、签到给多少积分。 */
@Serializable
data class SignStateDto(
    /** 账户可用积分（不是签到分）：积分页那个大数字用它。 */
    @SerialName("points") val points: Int = 0,
    @SerialName("signed") val signed: Int = 0,
    /** 签到能拿多少；老后端不下发这个字段，mapper 会回落到 [points]。 */
    @SerialName("sign_points") val signPoints: Int = 0,
)

/** 一条积分任务 `GET v1.user/task`。`done` 是"今日已完成"，不是"已领取"。 */
@Serializable
data class PointsTaskDto(
    @SerialName("key") val key: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("points") val points: Int = 0,
    @SerialName("done") val done: Int = 0,
    /** 目标次数（后台 `mac_task.task_target`），老后端不下发时按 1 处理。 */
    @SerialName("target") val target: Int = 1,
    /** 今日进度，0..target。 */
    @SerialName("progress") val progress: Int = 0,
    /** 1 = 今天已经领过（或签到/分享这类由接口直接发奖）。 */
    @SerialName("claimed") val claimed: Int = 0,
)

@Serializable
data class PointsTaskListDto(
    @SerialName("list") val list: List<PointsTaskDto> = emptyList(),
)

/**
 * 积分页首帧快照的载体：把 `v1.user/sign` 与 `v1.user/task` 的原始响应拼成一份存盘。
 *
 * 一半为 null 表示那个接口还没成功过；读取方按"缺 sign 就不算快照"处理。
 */
@Serializable
data class PointsSnapshotDto(
    @SerialName("sign") val sign: SignStateDto? = null,
    @SerialName("tasks") val tasks: PointsTaskListDto? = null,
)

/** 分享信息 `GET v1.user/shareInfo`：后台配的链接/标题/描述，字段可能为空串。 */
@Serializable
data class ShareInfoDto(
    @SerialName("share_url") val url: String = "",
    @SerialName("share_logo") val logo: String = "",
    @SerialName("share_title") val title: String = "",
    @SerialName("share_description") val description: String = "",
)
