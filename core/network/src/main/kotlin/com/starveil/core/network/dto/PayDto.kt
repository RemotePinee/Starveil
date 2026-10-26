package com.starveil.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 支付相关响应体，字段逐个对照后端返回与调用点：
 * - 会员价目表：`v1.user/groups`
 * - 下单：`v1.user/order`
 * - 充值提示：`v1.user/payTip`、`v1.user/goldTip`
 * - 卡密：`v1.user/buy`
 * - 单集：`v1.user/buypopedom`（旧声明是 `BaseResult<String>`）
 *
 * 全部字段带默认值：后端缺字段时必须能反序列化，由业务层降级。
 */

/**
 * 会员组表。`list` 是**对象不是数组**，键是组 id 的字符串（形如 `_$1`/`_$2`/`_$3`），
 * 只读键 `"3"`。
 */
@Serializable
data class MemberGroupCatalogDto(
    @SerialName("list") val list: Map<String, MemberGroupDto> = emptyMap(),
)

/**
 * 会员组。只搬运价目表需要的字段：`group_type` / `group_popedom` 是大段权限串，
 * 支付页不读，靠 `ignoreUnknownKeys` 丢弃。
 */
@Serializable
data class MemberGroupDto(
    @SerialName("group_id") val groupId: Int = 0,
    @SerialName("group_name") val groupName: String = "",
    @SerialName("group_status") val groupStatus: Int = 0,
    @SerialName("group_points_day") val pointsDay: Int = 0,
    @SerialName("group_points_week") val pointsWeek: Int = 0,
    @SerialName("group_points_month") val pointsMonth: Int = 0,
    @SerialName("group_points_year") val pointsYear: Int = 0,
)

/**
 * 下单响应（`v1.user/order` POST `price`）。
 *
 * 没有 `order_status`：那是查询接口（同路径 GET `order_code`）才有的字段，
 * 两个接口共用一个路径但响应体不同，不能合并成一个 DTO。
 */
@Serializable
data class OrderDto(
    @SerialName("user_id") val userId: Int = 0,
    @SerialName("order_code") val orderCode: String = "",
    @SerialName("order_price") val orderPrice: String = "",
    @SerialName("order_time") val orderTime: Long = 0,
    @SerialName("order_points") val orderPoints: Int = 0,
)

/**
 * 订单查询响应（`v1.user/order` GET `order_code`）。
 *
 * 与 [OrderDto] 同一路径、同一 DAO 方法名，只是 HTTP 方法与响应体不同：
 * 查询会多出 `order_id` / `order_status` / `order_pay_type` / `order_pay_time` /
 * `order_remarks`。只看 `order_status`，
 * 其余字段照样搬到模型，便于页面把待支付订单完整回显。
 */
@Serializable
data class OrderStatusDto(
    @SerialName("order_id") val orderId: Int = 0,
    @SerialName("user_id") val userId: Int = 0,
    @SerialName("order_status") val orderStatus: Int = 0,
    @SerialName("order_code") val orderCode: String = "",
    @SerialName("order_price") val orderPrice: String = "",
    @SerialName("order_time") val orderTime: Long = 0,
    @SerialName("order_points") val orderPoints: Int = 0,
    @SerialName("order_pay_type") val orderPayType: String = "",
    @SerialName("order_pay_time") val orderPayTime: Long = 0,
    @SerialName("order_remarks") val orderRemarks: String = "",
)

/** `v1.user/payTip`：`data` 只有一个 `url`，提示文案在响应壳的 `msg` 上。 */
@Serializable
data class PayTipDto(
    @SerialName("url") val url: String? = null,
)

/** `v1.user/goldTip`：`msg` 是最低提现额，`info` 是比例说明。 */
@Serializable
data class GoldTipDto(
    @SerialName("msg") val msg: String? = null,
    @SerialName("info") val info: String? = null,
)

/**
 * `v1.user/buy`（卡密兑换）。
 *
 * 老后端只在 `data.msg` 里给一句提示（"充值成功，增加积分【100】"）；
 * 新版在 `data` 上补了核销结果，老字段照样保留，不读新字段的调用方不受影响。
 * 四个新字段全部有默认值：老后端不返回时不应解析失败。
 */
@Serializable
data class BuyCardResultDto(
    @SerialName("msg") val msg: String = "",
    @SerialName("card_type") val cardType: String = "",
    @SerialName("card_value") val cardValue: Int = 0,
    @SerialName("group_id") val groupId: Int = 0,
    @SerialName("membership_end") val membershipEnd: Long = 0,
)

/**
 * `v1.user/group`（会员兑换/升级，旧 `upgradeGroup`）。
 *
 * 后端复用了卡密的 `CardBuyBean`：`data` 里只有 `msg`（"开通成功"之类）。
 * 单独建一个 DTO 而不是复用 [BuyCardResultDto]，避免后人以为两个接口
 * 的字段会同步变化——它们只是**当前**同形。
 */
@Serializable
data class GroupUpgradeDto(
    @SerialName("msg") val msg: String = "",
)
