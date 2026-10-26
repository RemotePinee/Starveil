package com.starveil.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 金币提现响应体。
 *
 * 提现申请 `POST v1.user/goldWithdrawApply` 的 `data` 只有一个 `info`
 * （样例值 "提交成功"），旧页直接 toast 它。
 * [GoldTipDto] 不在这里：它在 `v1.user/goldTip`，与支付页共用，留在 `PayDto.kt`。
 */
@Serializable
data class WithdrawApplyResultDto(
    @SerialName("info") val info: String = "",
)
