package com.starveil.feature.user

import androidx.compose.runtime.Immutable
import com.starveil.core.model.PointsTask

/**
 * 积分中心状态。不可变，经 StateFlow 暴露。
 *
 * [tasks] 是后端 `v1.user/task` 的今日任务（已过滤掉"签到"——它由顶部按钮承担，
 * 不再在列表里重复一行）。
 */
@Immutable
data class PointsUiState(
    val points: Int = 0,
    val signedToday: Boolean = false,
    val signPoints: Int = 0,
    val tasks: List<PointsTask> = emptyList(),
    /** 分享文案，来自 `v1.user/shareInfo`；空串表示后台没配，UI 回落到应用名。 */
    val shareUrl: String = "",
    val shareTitle: String = "",
    val isLoading: Boolean = true,
    /** 正在提交的任务 key（"sign" / "watch" / "share"），非空时按钮转圈并禁用。 */
    val submitting: String? = null,
    val errorMessage: String? = null,
) {

    /** 已经有可展示内容（首帧快照或上次成功响应）；此时刷新不该再整屏转圈。 */
    val hasContent: Boolean
        get() = tasks.isNotEmpty() || points > 0 || signedToday

    /** 顶部签到按钮是否可点：已签、加载中、正在提交都不行。 */
    val canSign: Boolean
        get() = !signedToday && !isLoading && submitting == null

    val signLabel: String
        get() = when {
            signedToday -> "今日已签到"
            signPoints > 0 -> "签到 +$signPoints"
            else -> "签到"
        }
}
