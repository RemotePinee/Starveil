package com.starveil.feature.user

import com.starveil.core.model.WithdrawChannel
import com.starveil.core.model.WithdrawTips

/**
 * 金币提现页状态。不可变，经 `StateFlow` 暴露（AGENTS.md 第 5 节）。
 *
 * 提现页曾把输入放在四个 EditText 里、判空放在点击回调里、
 * 提示靠 Toast（`:167-183`）；这里全部搬进状态，页面只渲染与上抛动作：
 * - 金额**保留用户原始输入**，合法性由 [amountValue] / [amountError] 判定，
 * 不在输入时偷偷过滤字符——旧页 `money.toInt()` 抛出时只弹一句"必须是数字"，
 * 用户还能看见自己输错了什么；
 * - [submitted] 是"已提交，等待审核"的成功态，不是一次性事件：审核结果本来就不在
 * 接口响应里（见 `WithdrawRepository.apply`），页面需要常驻这句话。
 *
 * @param tips `v1.user/goldTip` 的解析结果；null 表示还没拉到（加载中或失败）
 * @param amount 用户输入的金额原文
 * @param account 收款账号（支付宝账号 / 微信号）
 * @param name 收款姓名
 * @param channel 收款渠道，旧页默认支付宝（、`:72`）
 * @param loadError 说明文案加载失败，整页可重试
 * @param submitted 申请已提交成功
 * @param qrcodePath 收款码站内相对路径，由 `v1.upload/withdraw` 返回
 * @param qrcodePreview 本地图片 uri，只用于上传成功后的缩略图预览
 * @param uploadingQrcode 收款码上传中
 * @param pendingConfirm 上一笔「结果未知」的申请；非空时禁止再次提交
 * @param requestId 一次性幂等键，结果未知时保留，成功或明确失败后清空
 */
data class WithdrawUiState(
    val tips: WithdrawTips? = null,
    val amount: String = "",
    val account: String = "",
    val name: String = "",
    /** 收款码相对路径（`/upload/withdraw/...`）；空串表示没传，提交时不下发该字段。 */
    val qrcodePath: String = "",
    /** 本地选中图片的 uri，绝不参与提交。 */
    val qrcodePreview: String = "",
    /** 上传中：按钮禁用，提交也一起拦住，避免"传了一半就提交"。 */
    val uploadingQrcode: Boolean = false,
    val channel: WithdrawChannel = WithdrawChannel.ALIPAY,
    val isLoading: Boolean = true,
    val isSubmitting: Boolean = false,
    val loadError: String? = null,
    val submitted: Boolean = false,
    /**
     * 结果未知的上一笔申请：非空时禁止再次提交。
     *
     * 「未知」指请求发出去了但没拿到确定答复（断网、超时、5xx、响应解析失败），
     * 服务端可能已经落库。这类失败不能当普通失败解锁表单，否则用户再点一次就是第二笔申请。
     */
    val pendingConfirm: PendingWithdraw? = null,
    /**
     * 本次提交申请的一次性幂等键，结果未知时保留，成功或后端明确拒绝后清空。
     *
     * 服务端 `goldWithdrawApply` 目前没有幂等字段（只收 num/type/account/realname/qrcode，
     * 见 ），所以先只留在状态里；
     * 服务端加上 `request_id` + 唯一索引后由 `WithdrawRepository.apply` 透传，客户端语义不变。
     */
    val requestId: String = "",
) {

    /** 后端给的最低提现额（元）；0 表示后端没给门槛。 */
    val minAmountYuan: Int get() = tips?.minAmountYuan ?: 0

    /** 可提现金额（元），来自 `goldTip.info` 里的"当前可提现金额 x 元"。 */
    val availableYuan: Int get() = tips?.availableYuan ?: 0

    /**
     * 超过可提现余额；后端没给数值（[availableYuan] 为 0）时不拦——线上 `goldTip.info`
     * 目前只回"提现比例 x 金币 = 1 元"，没有余额数字
     * 真拦会把所有人挡在门外。
     *
     * 单笔上限同理不做：服务端没有 `withdraw_max_yuan` 这类配置，后台设置只有
     * `withdraw_min_yuan` / `gold_per_yuan`。服务端下发上限后在这里补一条比较即可。
     */
    val isOverBalance: Boolean
        get() = availableYuan > 0 && (amountValue ?: 0) > availableYuan

    /** 余额提示；接口没给数值时降级成"以实际到账为准"的说明文案。 */
    val balanceText: String
        get() = tips?.let { tip ->
            if (tip.availableYuan > 0) "可提现 ${tip.availableYuan} 元" else "可提现金额以账户为准"
        } ?: "正在获取提现说明"

    /** 金额原文 -> 整数。小数、字母、超长数字都返回 null，交给 [amountError] 解释。 */
    val amountValue: Int? get() = amount.trim().toIntOrNull()

    /** 金额是否低于后端门槛；空输入不算。 */
    val isBelowMinimum: Boolean
        get() = amount.isNotBlank() && tips?.isBelowMinimum(amountValue ?: 0) == true

    /** 金额校验文案；空输入不报错，只是还不能提交。 */
    val amountError: String?
        get() {
            val value = amountValue
            return when {
                amount.isBlank() -> null
                value == null -> "请输入整数金额"
                value <= 0 -> "提现金额必须大于 0"
                isBelowMinimum -> "最低提现 $minAmountYuan 元"
                isOverBalance -> "超出可提现金额（可提现 $availableYuan 元）"
                else -> null
            }
        }

    /** 收款信息是否填全。 */
    val hasPayee: Boolean get() = account.isNotBlank() && name.isNotBlank()

    /** 金额合法：大于 0、不低于门槛、不超过可提现余额。 */
    val hasValidAmount: Boolean get() = (amountValue ?: 0) > 0 && !isBelowMinimum && !isOverBalance

    /** 已上传收款码。 */
    val hasQrcode: Boolean get() = qrcodePath.isNotBlank()

    /** 可提交：表单填全、没有在飞的上传/提交，也没有「上次结果未知」的锁定。 */
    val canSubmit: Boolean
        get() = !isSubmitting &&
            !uploadingQrcode &&
            pendingConfirm == null &&
            hasValidAmount &&
            hasPayee

    /** 提交按钮文案，与旧页的"完成"对齐但带上金额，减少误提现。 */
    val submitLabel: String
        get() = if (pendingConfirm != null) {
            "请先确认上次提交结果"
        } else {
            amountValue?.takeIf { hasValidAmount }?.let { "提交提现 ¥$it" } ?: "提交提现"
        }
}

/**
 * 一次提现申请的可重放快照。
 *
 * 结果未知时整份留在 [WithdrawUiState.pendingConfirm] 里，用户确认后按原样重发：
 * 金额、收款人、收款码都不能取"页面上现在是什么"，否则用户在等待期间改了表单就会串单。
 */
data class WithdrawRequest(
    val amount: Int,
    val channel: WithdrawChannel,
    val account: String,
    val name: String,
    val qrcode: String,
)

/** 上一笔「结果未知」的申请：横幅文案 + 可重放的原请求。 */
data class PendingWithdraw(
    val request: WithdrawRequest,
    val message: String,
)

/**
 * 结果未知时的统一说法：状态条与提交拦截共用。
 *
 * 客户端只能保证「不确定时不再发第二次」；彻底去重仍要服务端给
 * `goldWithdrawApply` 加幂等键 + 唯一索引（见 [WithdrawUiState.requestId]）。
 */
internal const val PENDING_CONFIRM_MESSAGE = "上次提交结果未知，请先查询提现记录，确认后再提交"

/**
 * 一次性事件：失败提示（校验失败、提交失败）。
 *
 * 放进 `UiState` 会让 Toast 在旋屏后重放，所以走 `Channel`
 * （AGENTS.md 第 5 节：一次性事件不进 UiState）。
 */
sealed interface WithdrawEvent {
    data class Message(val text: String) : WithdrawEvent
}
