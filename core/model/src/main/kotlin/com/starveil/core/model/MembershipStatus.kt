package com.starveil.core.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会员状态。**唯一**的会员判定与文案来源：
 * "我的"页会员卡（`UserCenterMemberCard`）和会员页 Hero（`PayMembershipHero`）都调这里，
 * 同一个账号在两张卡上不可能出现两种说法。
 *
 * 判定只看服务端 `is_vip`（[UserProfile.isVip]），**不拿 [UserProfile.memberEndTime] 自算**：
 * 后台新建的游客账号 `user_end_time` 也可能有值，按时间戳判会把非会员写成"有效期至 X"。
 */
sealed interface MembershipStatus {

    /** 登录态还没读出来（本地会话 restore 未返回）。页面显示占位，不要断言"未登录"。 */
    data object Pending : MembershipStatus

    /** 没有本地会话。 */
    data object Guest : MembershipStatus

    /** 已登录但不是会员；服务端 `is_vip == false`，或资料还没到/读取失败。 */
    data object NotSubscribed : MembershipStatus

    /** 是会员但服务端没给到期时间（后端没有"永久会员"标志位），不编造日期。 */
    data object ActiveWithoutExpiry : MembershipStatus

    /** 会员且在有效期内。 */
    data class Active(val endTimeSeconds: Long) : MembershipStatus

    /** 星标、徽章、广告豁免都看它。 */
    val isMember: Boolean
        get() = this is Active || this is ActiveWithoutExpiry

    /** 会员卡副标题文案，两个页面共用。 */
    val label: String
        get() = when (this) {
            Pending -> LABEL_PENDING
            Guest -> LABEL_GUEST
            NotSubscribed -> LABEL_NOT_SUBSCRIBED
            ActiveWithoutExpiry -> LABEL_ACTIVE_WITHOUT_EXPIRY
            is Active -> LABEL_EXPIRY_PREFIX + endTimeSeconds.toMemberDate()
        }
}

/**
 * 由资料与登录态推导会员状态。
 *
 * @param isLoggedIn 本地是否有会话；**null 表示还没读出来**（首帧），此时返回
 *   [MembershipStatus.Pending]，页面先显示占位，避免已登录用户先看到"登录后查看会员状态"再跳变。
 *
 * 已登录但 [profile] 为 null（资料未到或读取失败）按 [MembershipStatus.NotSubscribed] 处理：
 * 会员页首帧就给出稳定文案，不再闪一次错误引导。
 */
fun UserProfile?.toMembershipStatus(isLoggedIn: Boolean?): MembershipStatus = when {
    isLoggedIn == null -> MembershipStatus.Pending
    this == null -> if (isLoggedIn) MembershipStatus.NotSubscribed else MembershipStatus.Guest
    isVip && memberEndTime > NO_MEMBER_END -> MembershipStatus.Active(memberEndTime)
    isVip -> MembershipStatus.ActiveWithoutExpiry
    else -> MembershipStatus.NotSubscribed
}

/** 秒级时间戳 → yyyy-MM-dd。每次新建 formatter：`SimpleDateFormat` 非线程安全。 */
private fun Long.toMemberDate(): String =
    SimpleDateFormat(MEMBER_DATE_PATTERN, Locale.getDefault())
        .format(Date(this * MILLIS_PER_SECOND))

private const val LABEL_PENDING = "——"
private const val LABEL_GUEST = "登录后查看会员状态"
private const val LABEL_NOT_SUBSCRIBED = "尚未开通会员，开通后可畅看会员专区"
private const val LABEL_ACTIVE_WITHOUT_EXPIRY = "会员有效中"
private const val LABEL_EXPIRY_PREFIX = "会员有效期至 "
private const val MEMBER_DATE_PATTERN = "yyyy-MM-dd"
private const val MILLIS_PER_SECOND = 1000L
private const val NO_MEMBER_END = 0L
