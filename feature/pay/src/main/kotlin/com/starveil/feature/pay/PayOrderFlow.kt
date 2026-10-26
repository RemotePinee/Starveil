package com.starveil.feature.pay

import com.starveil.core.common.AppResult
import com.starveil.core.data.PayRepository
import com.starveil.core.model.PayOrderStatus
import com.starveil.core.model.PayPlan

/**
 * 订单收口：查单 → 判断是否已支付 → 兑换会员。
 *
 * 从 `PayViewModel` 里拆出来有两个原因：ViewModel 的类体行数上限（detekt LargeClass 200），
 * 以及这段流程是纯"接口 → 结果"的转换，不碰 UI 状态，单独测更容易。
 *
 * 业务口径来自旧 、`:273-294`：
 * 回跳后查 `v1.user/order?order_code=`，`order_status == 0` 表示未支付；
 * 已支付再调 `v1.user/group` 兑换会员时长。
 */
internal class PayOrderFlow(private val payRepository: PayRepository) {

    /** 下单：返回订单号与 H5 地址，由调用方决定怎么打开（:app 用系统浏览器）。 */
    suspend fun place(plan: PayPlan, payment: String): AppResult<com.starveil.core.model.PayOrder> =
        payRepository.pay(plan.id, payment)

    /**
     * 回跳结算。
     *
     * @param plan 本次下单用的套餐；为 null（进程重建后丢了套餐）时只报支付结果，不兑换。
     */
    suspend fun settle(orderCode: String, plan: PayPlan?): SettlementResult =
        when (val status = payRepository.orderStatus(orderCode)) {
            is AppResult.Failure -> SettlementResult.Failed(status.error.toPayMessage())
            is AppResult.Success -> settlePaid(status.value, plan)
        }

    private suspend fun settlePaid(status: PayOrderStatus, plan: PayPlan?): SettlementResult = when {
        !status.isPaid -> SettlementResult.Settled(status, activated = false, message = UNPAID_MESSAGE)
        // 只在选了套餐时才兑换
        plan == null -> SettlementResult.Settled(status, activated = false, message = "支付成功")
        else -> activate(status, plan)
    }

    private suspend fun activate(status: PayOrderStatus, plan: PayPlan): SettlementResult =
        // 带上订单号：服务端据此校验已支付并写 opened_at，重复兑换是幂等的。
        when (val upgrade = payRepository.upgradeMembership(plan.groupId, plan.period, status.orderCode)) {
            is AppResult.Success ->
                SettlementResult.Settled(status, activated = true, message = "支付成功，会员已开通")

            is AppResult.Failure -> SettlementResult.Settled(
                status = status,
                activated = false,
                message = "支付成功，会员兑换失败：" + upgrade.error.toPayMessage(),
            )
        }
}

/** 未支付提示。只清订单号不报错，这里给一句可操作提示。 */
private const val UNPAID_MESSAGE = "订单未支付，可重新选择套餐支付"

/** 回跳结算结果。`Failed` 表示连订单状态都没拿到，调用方只提示不重试。 */
internal sealed interface SettlementResult {

    data class Settled(
        val status: PayOrderStatus,
        val activated: Boolean,
        val message: String,
    ) : SettlementResult

    data class Failed(val message: String) : SettlementResult
}
