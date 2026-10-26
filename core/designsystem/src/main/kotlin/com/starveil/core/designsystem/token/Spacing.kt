package com.starveil.core.designsystem.token

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 间距令牌：4dp 基准栅格。
 *
 * 页面代码只允许用这里的值，禁止裸 dp（AGENTS.md 第 3 节）。
 */
object Spacing {
    /** 零间距：条件内边距（全屏/非全屏）用它，避免页面里写裸 0.dp。 */
    val none: Dp = 0.dp
    /** 1dp 描边宽度：跨背景悬浮的卡片靠它勾轮廓，比分隔线还细。 */
    val hairline: Dp = 1.dp
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp

    /**
     * 列表行高：设置 / 功能列表这类"图标 + 文案 + 箭头"的行统一 52dp。
     *
     * 不用 `padding` 现算：行高一改要动每一行，且五格数据行与列表行必须对齐。
     */
    val rowHeight: Dp = 52.dp

    /** 页面级横向边距的默认值。 */
    val pageHorizontal: Dp = lg
}

/**
 * 图标尺寸令牌。禁止用位图拉伸当图标。
 */
object IconSize {
    val sm: Dp = 16.dp

    /** 列表行内图标 20dp：比 [md] 小一档，与 52dp 行高、15sp 文案视觉匹配。 */
    val item: Dp = 20.dp
    val md: Dp = 24.dp
    val lg: Dp = 32.dp
    /** 右栏图标：比普通大图标略大，保持触控区不变。 */
    val xl: Dp = 36.dp
}
