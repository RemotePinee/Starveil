package com.starveil.core.model

/**
 * 卡密核销结果（`POST v1.user/buy` 响应壳的 `data`）。
 *
 * 后端只认单字段 `card_pwd`，并把**卡号当卡密**用；核销成功后回传卡类型与面值，
 * 客户端据此区分"会员延长 N 天"与"获得 N 金币"，不再像那样写死一句
 * "卡密兑换成功，会员已到账"。
 *
 * 老后端不返回这些字段，因此每个字段都有默认值，映射层不做非空断言。
 */
data class CardRedeem(
    /** 卡密类型；后端只发 vip/gold，其它值映射为 null，按"普通成功"兜底。 */
    val cardType: CardType? = null,
    /** 面值：[CardType.VIP] 是天数，[CardType.GOLD] 是金币数。 */
    val cardValue: Int = 0,
    /** 核销后的会员组 id（`mac_user.group_id` 多组串的最后一个）。 */
    val groupId: Int = 0,
    /** 会员到期时间，秒级时间戳；金币卡固定 0，表示没有改动会员到期时间。 */
    val membershipEnd: Long = 0,
)

/** 卡密类型，与后端表 `cuican_card.card_type` 的取值一致。 */
enum class CardType(val code: String) {
    VIP("vip"),
    GOLD("gold"),
    ;

    companion object {

        /** 未知或空串返回 null：后端数据异常时客户端走兜底提示，不抛异常。 */
        fun fromCode(raw: String): CardType? =
            entries.firstOrNull { it.code == raw.trim().lowercase() }
    }
}
