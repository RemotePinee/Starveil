package com.starveil.core.network.mapper

import com.starveil.core.model.CardRedeem
import com.starveil.core.model.CardType
import com.starveil.core.model.PayOrder
import com.starveil.core.model.PayOrderStatus
import com.starveil.core.model.PayPeriod
import com.starveil.core.model.PayPlan
import com.starveil.core.network.dto.BuyCardResultDto
import com.starveil.core.network.dto.MemberGroupDto
import com.starveil.core.network.dto.OrderDto
import com.starveil.core.network.dto.OrderStatusDto

/**
 * 支付 DTO -> 领域模型。
 *
 * 两条旧行为必须保留：
 * 1. 四个时长价都是 `group_points_*`，支付页除以 10 当元用，整数除法
 *    不足 10 积分折不出元，直接不出这个档位。
 * 2. 套餐编号是本地顺序号，后端没有套餐 id（见 `PayPlan` 注释）。
 */

/** 价目顺序固定日/周/月/年，编号 1..4（下标 + 1），与 [PayPeriod] 声明顺序一致。 */
fun MemberGroupDto.toPlans(): List<PayPlan> {
    val tiers = listOf(
        PayPeriod.DAY to pointsDay,
        PayPeriod.WEEK to pointsWeek,
        PayPeriod.MONTH to pointsMonth,
        PayPeriod.YEAR to pointsYear,
    )
    return tiers.mapIndexedNotNull { index, (period, points) ->
        val yuan = points / POINTS_PER_YUAN
        if (yuan <= 0) return@mapIndexedNotNull null
        PayPlan(id = index + 1, groupId = groupId, period = period, priceYuan = yuan, points = points)
    }
}

fun OrderDto.toDomain(payUrl: String): PayOrder = PayOrder(
    orderCode = orderCode,
    priceYuan = orderPrice,
    points = orderPoints,
    payUrl = payUrl,
)

/**
 * 订单查询响应 -> 领域模型。字段一对一搬运，不做换算：
 * `order_price` 是字符串（"10.00"）、时间是秒级时间戳。
 *
 * 未支付判定不在这里做，[com.starveil.core.model.PayOrderStatus.isPaid] 统一收口，
 * 避免"0 是未支付"这个后端约定散落在映射层和页面层两处。
 */
fun OrderStatusDto.toDomain(): PayOrderStatus = PayOrderStatus(
    orderId = orderId,
    userId = userId,
    orderStatus = orderStatus,
    orderCode = orderCode,
    priceYuan = orderPrice,
    orderTime = orderTime,
    points = orderPoints,
    payType = orderPayType,
    payTime = orderPayTime,
    remarks = orderRemarks,
)

/**
 * 卡密核销结果 -> 领域模型。
 *
 * `card_type` 是自由字符串，"未知值"必须能安全落到 null（后端数据被改过时页面
 * 仍要能给出"兑换成功"的兜底提示），所以类型判定收在 [CardType.fromCode] 里。
 */
fun BuyCardResultDto.toDomain(): CardRedeem = CardRedeem(
    cardType = CardType.fromCode(cardType),
    cardValue = cardValue,
    groupId = groupId,
    membershipEnd = membershipEnd,
)

/** 按 10 积分 = 1 元折算。 */
private const val POINTS_PER_YUAN = 10
