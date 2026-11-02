package com.starveil.feature.shortplay

import androidx.compose.ui.unit.Dp
import com.starveil.core.designsystem.token.Spacing

/**
 * 分类条的行高档位：剧场页压在封面上，分类页两级要靠得近。
 *
 * 只收「行高 + 用哪级字阶」；颜色与下划线开关各走各的参数——三者正交，
 * 混进这个枚举会让剧场页想关下划线时被迫连字号一起改。
 */
internal enum class ShortPlayCategoryRowLayout(
    /** 整行高度：下划线托在文字下方，宽松档必须留出这条线的位置。 */
    val height: Dp,
    /** 文字降一档（titleLarge → titleMedium）：分类页靠字号差读作「一级的子项」。 */
    val secondaryText: Boolean,
) {
    /** 剧场页：48dp + 一级字阶，与首页那条分类行逐项对齐。 */
    Theater(height = Spacing.xxl + Spacing.lg, secondaryText = false),

    /** 分类页一级：32dp，只够托住 17sp 文字，与下面的二级行隔出约 8dp。 */
    Primary(height = Spacing.xxl, secondaryText = false),

    /** 分类页二级：28dp（24 + 4，现有档位里没有正好这一档）+ 降一档字阶。 */
    Secondary(height = Spacing.xl + Spacing.xs, secondaryText = true),
}
