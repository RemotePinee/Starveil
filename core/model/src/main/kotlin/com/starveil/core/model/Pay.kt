package com.starveil.core.model

/**
 * 支付领域模型（会员套餐 / 在线充值 / 卡密 / 单集购买）。
 *
 * 字段口径写在各自 KDoc 里，
 * 后端真实字段名与映射在 `:core:network` 的 `PayDto.kt` / `PayMapper.kt`。
 */

/**
 * 会员时长档位。
 *
 * `code` 是旧 `v1.user/group` 的 `long` 字段值；
 * `days` 只用于折算"低至 x 元/天"，年卡按 360 天算而不是 365
 */
enum class PayPeriod(val code: String, val title: String, val days: Int) {
    DAY("day", "日卡", 1),
    WEEK("week", "周卡", 7),
    MONTH("month", "月卡", 30),
    YEAR("year", "年卡", 360),
}

/**
 * 会员套餐。
 *
 * 后端没有"套餐 id"：`v1.user/groups` 下发的是「会员组 -> 四个时长价」，
 * 只读键 `"3"` 的 VIP 会员组，把 `group_points_day/week/month/year`
 * 除以 10 当元展示。
 * [id] 是本模块按档位顺序给的本地编号（1..4），只用于选中态与下单查价，
 * **不是后端字段**，下单接口只认金额（见 [PayOrder]）。
 */
data class PayPlan(
    val id: Int,
    val groupId: Int,
    val period: PayPeriod,
    /** 按 `group_points_week / 10` 取整，单位元。 */
    val priceYuan: Int,
    /** 后端原始积分值，退回积分口径时用得上。 */
    val points: Int,
) {
    /** 低至 x 元/天，两位小数；用 DecimalFormat("0.00")。 */
    val dailyPrice: String get() = "低至 %.2f 元/天".format(priceYuan.toFloat() / period.days)
}

/**
 * 下单结果。
 *
 * 后端只返回订单号，**不返回支付地址**：自己拼
 * `v1.user/pay?payment={渠道}&order_code={订单号}` 交给 WebView
 * [payUrl] 就是拼好的 H5 地址，
 * 由页面回调上抛给 `:app` 打开，本模块不启动浏览器。
 */
data class PayOrder(
    val orderCode: String,
    /** 后端 `order_price` 是字符串（"10.00"），原样保留，不做本地格式化。 */
    val priceYuan: String,
    val points: Int,
    val payUrl: String,
)

/**
 * 订单状态（`GET v1.user/order?order_code=`）。
 *
 * 后端语义只有两种：`order_status == 0` 未支付，其它值即已支付——写的是
 * `if (data.order_status == 0) "未支付" else "支付成功"`，没有第三个分支，
 * 所以 [isPaid] 也不枚举其它状态，避免凭空造值。
 *
 * 字段名按后端 bean 逐条搬运，[priceYuan] 与 [payType] 后端都是字符串，
 * 保持原样（"10.00"、""），不在模型层格式化或转数值。
 */
data class PayOrderStatus(
    val orderId: Int,
    val userId: Int,
    /** 后端原始 `order_status`，0 = 未支付。 */
    val orderStatus: Int,
    val orderCode: String,
    val priceYuan: String,
    /** 下单时间，秒级时间戳；0 表示后端没给。 */
    val orderTime: Long,
    /** 本单增加的积分（`order_points`）。 */
    val points: Int,
    /** 支付渠道码，未支付时为空串。 */
    val payType: String,
    /** 支付时间，秒级时间戳；0 表示未支付。 */
    val payTime: Long,
    val remarks: String,
) {
    /** 旧判定：非 0 即已支付。 */
    val isPaid: Boolean get() = orderStatus != UNPAID_ORDER_STATUS

    /** 页面展示用文案，与旧页的"未支付"/"支付成功"两句对齐。 */
    val statusText: String get() = if (isPaid) "已支付" else "待支付"

    companion object {
        /** 后端未支付状态码，唯一硬编码的那个 0。 */
        const val UNPAID_ORDER_STATUS = 0
    }
}

/**
 * 充值提示，合并了的两个接口：
 * - `v1.user/payTip`：`data.url` 是在线充值外部页面，
 * 提示文案在响应壳的 `msg` 上，不在 `data` 里；
 * - `v1.user/goldTip`：`msg` 是最低提现额、
 * `info` 是提现比例说明，与支付页无关，留在模型里给金币页复用。
 */
data class PayTips(
    val payHint: String,
    val payUrl: String,
    val goldHint: String,
    val goldInfo: String,
) {
    /** 只在 url 非空时显示在线充值入口。 */
    val hasExternalRecharge: Boolean get() = payUrl.isNotBlank()
}
