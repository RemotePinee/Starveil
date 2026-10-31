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

    /**
     * 侧栏（抽屉）宽度：窄屏按屏幕比例收，宽屏封顶这个值。
     *
     * 不再用 [rowHeight] 乘系数凑宽度——那样改行高会把侧栏一起带歪。
     */
    val drawerWidth: Dp = 280.dp

    /** 页面级横向边距的默认值。 */
    val pageHorizontal: Dp = lg

    // -----------------------------------------------------------------------
    // 组件尺寸：不属于 4dp 栅格的"一次性尺寸"，但同样是页面不许硬写的数字。
    // 每个值都来自具体页面，加之前先确认没有现成档位能用。
    // -----------------------------------------------------------------------

    /** 表单控件高度（输入框 / 主按钮）：登录三页必须一样高，故收成令牌。 */
    val control: Dp = 56.dp

    /** 底栏图标模式的高度：图标 + 文案 + 间距的合计。 */
    val tabBarHeightIcons: Dp = 64.dp

    /** 底栏纯文字模式的高度：没有图标占位，收到这一档。 */
    val tabBarHeightText: Dp = 44.dp

    /** 开屏中间动画的显示尺寸：素材 468×468，等比缩到这一档。 */
    val splashAnimation: Dp = 168.dp

    /**
     * 开屏底部 lockup 的尺寸。
     *
     * 必须与 `splash_window.xml` 里 `<item>` 的 `android:width` / `android:height`
     * 逐字相同，两屏之间这个 logo 才是"同一个元素在延续"。
     */
    val splashLogoWidth: Dp = 160.dp
    val splashLogoHeight: Dp = 69.dp

    /** 剧场页搜索胶囊展开后的宽度：411dp 机身上占一半，刻意不铺满整行。 */
    val searchBarExpandedWidth: Dp = 208.dp

    /** 全屏选台面板里左侧分类栏的宽度：分组名本来就短，再窄会挤掉每组数量。 */
    val livePickerGroupWidth: Dp = 96.dp

    /** 弹幕行高：与优酷同源，y 直接取「行号 × (行高 + 行间距)」。 */
    val danmuLineHeight: Dp = 24.dp

    /** 弹幕行间距：在行高之上再留的空隙。 */
    val danmuLineGap: Dp = 6.dp
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

    /** 底栏 tab 图标 22dp：与 64dp 图标栏对齐，比 [item] 大一档。 */
    val tab: Dp = 22.dp
}
