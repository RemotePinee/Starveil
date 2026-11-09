package com.starveil.app.navigation

/**
 * 「我的」页这些入口没有会话就是空壳：收藏、设置、积分、提现、会员售卖。
 *
 * 判定只此一处：入口点击、以及以后新增的入口都问它，别再各写一串 `label.contains`。
 * 用**包含**而不是相等——后台配的入口文案会带修饰词（"我的收藏"、"会员中心"）。
 */
internal fun entryRequiresAccount(label: String): Boolean =
    ACCOUNT_ENTRY_KEYWORDS.any(label::contains)

private val ACCOUNT_ENTRY_KEYWORDS = listOf(
    "收藏",
    "积分",
    "提现",
    "设置",
    "会员",
    "续费",
    "卡密",
)
