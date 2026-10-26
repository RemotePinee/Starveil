package com.starveil.feature.user

import com.starveil.core.model.UserCenterVariant

/**
 * 变体 → 结构的映射表。
 *
 * 6 套配置本体在同包的 `UserCenterLayoutVariants.kt`（一个对象塞 6 套会超过
 * detekt 的单类 200 行上限，所以拆成顶层常量）。
 * 数据来源见 [UserCenterLayout] 的表格，每个变体上标了对应的旧布局文件名与关键行号。
 */
object UserCenterLayouts {

    /**
     * 卡密兑换入口。
     *
     * 6 套皮肤都没有这条（历史版本只在会员页里能兑换），但兑换是用户中心的常规能力，
     * 所以统一追加一段；点击由 `:app` 按文案路由到现有支付页（`PAY_ROUTE`），不新建页面。
     */
    private val redeemSection = UserCenterSection(
        id = UserCenterSectionId.TOOL_GRID,
        title = "卡密",
        entries = listOf(UserCenterEntry(label = "卡密兑换")),
        columns = 1,
        style = SectionStyle.LIST_ROW,
    )

    fun of(variant: UserCenterVariant): UserCenterLayout = layoutOf(variant).withRedeemEntry()

    private fun layoutOf(variant: UserCenterVariant): UserCenterLayout = when (variant) {
        UserCenterVariant.A -> userCenterLayoutA
        UserCenterVariant.B -> userCenterLayoutB
        UserCenterVariant.C -> userCenterLayoutC
        UserCenterVariant.D -> userCenterLayoutD
        UserCenterVariant.E -> userCenterLayoutE
        UserCenterVariant.F -> userCenterLayoutF
    }

    /** 统一插在"退出登录"之前；没有退出区块时追加到末尾。 */
    private fun UserCenterLayout.withRedeemEntry(): UserCenterLayout {
        val logoutIndex = sections.indexOfFirst { it.id == UserCenterSectionId.LOGOUT_ENTRY }
        val merged = if (logoutIndex < 0) {
            sections + redeemSection
        } else {
            sections.toMutableList().apply { add(logoutIndex, redeemSection) }
        }
        return copy(sections = merged)
    }
}
