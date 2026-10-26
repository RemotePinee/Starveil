package com.starveil.core.model

/**
 * 金币提现领域模型。
 *
 * 来源：提现页，接口是 `v1.user/goldTip`（提现说明）
 * 与 `v1.user/goldWithdrawApply`（提交申请），字段映射在 `:core:network` 的
 * `WithdrawMapper`。后端把说明文案直接塞在 `msg` / `info` 里，金额是中文串，
 * 所以两个数值字段是解析出来的，解析不到时降级为 0（见 [WithdrawTips]）。
 */

/**
 * 收款渠道。是下拉框 `arrayListOf("支付宝", "微信")`，
 * 下标 + 1 就是下发的 `type`（、`:52-57`）。
 */
enum class WithdrawChannel(val code: Int, val title: String) {
    ALIPAY(1, "支付宝"),
    WECHAT(2, "微信"),
}

/**
 * 提现说明，来自 `v1.user/goldTip`。
 *
 * 后端真实返回（后端注释里的样例）：
 * ```
 * msg : 最低提现20元
 * info : 当前可提现金额5000元，提现比例10金币=1元
 * ```
 *
 * - [minAmountYuan] 从 `msg` 里取"最低提现"后面的整数，解析不到为 0，
 * 0 表示"后端没给门槛"，页面不做下限拦截；
 * - [availableYuan] 从 `info` 里取"可提现金额"后面的整数，解析不到为 0；
 * - [ratioText] 保留 `info` 原文，比例说明（10 金币 = 1 元）是文案不是公式，
 * 客户端不参与折算——旧页也是原样显示。
 */
data class WithdrawTips(
    val minAmountYuan: Int,
    val availableYuan: Int,
    val ratioText: String,
    /** `msg` 原文，用于提示"最低提现 x 元"。 */
    val hint: String,
) {
    /** 后端给了门槛且金额低于门槛。 */
    fun isBelowMinimum(amount: Int): Boolean = minAmountYuan > 0 && amount < minAmountYuan
}
