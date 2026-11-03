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

    fun of(variant: UserCenterVariant): UserCenterLayout = layoutOf(variant)

    private fun layoutOf(variant: UserCenterVariant): UserCenterLayout = when (variant) {
        UserCenterVariant.A -> userCenterLayoutA
        UserCenterVariant.B -> userCenterLayoutB
        UserCenterVariant.C -> userCenterLayoutC
        UserCenterVariant.D -> userCenterLayoutD
        UserCenterVariant.E -> userCenterLayoutE
        UserCenterVariant.F -> userCenterLayoutF
    }
}
