package com.starveil.core.designsystem.token

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 扩展语义色：Material 的 colorScheme 表达不了、但业务需要一致性的颜色。
 *
 * 页面用法：`starveilExtraColors.textMuted`
 * 禁止在页面直接引用 [Palette]。
 *
 * 暗色是主形态；中性色骨架固定，皮肤只换 [accent] 与 [badge]。
 */
@Immutable
data class StarveilExtraColors(
    /** 会员金。旧字段名保留，页面已在用。 */
    val brandGold: Color,
    /** 浮层 / 导航栏底色，比页面底色亮一档。 */
    val surfaceElevated: Color,
    /** 卡片底色。 */
    val surfaceCard: Color,
    /** 次级文字。 */
    val textMuted: Color,
    /** 三级文字 / 元信息。 */
    val textFaint: Color,
    val divider: Color,
    /** 深色遮罩，用于弹层。 */
    val scrimStrong: Color,
    /** 海报叠字渐变的两端。 */
    val heroScrimStart: Color,
    val heroScrimEnd: Color,
    /** 胶囊 / 标签底色。 */
    val chipBackground: Color,
    /** 角标（TOP、独播、活动）主色。 */
    val badge: Color,
    /** 皮肤强调色，与 colorScheme.primary 同源，供渐变与 Canvas 使用。 */
    val accent: Color,
    /** 弹幕可选色（发送时转 colorHex，面板上作色块）。 */
    val danmuColors: List<Color> = Palette.DanmuSwatches,
    /**
     * 弹幕正文颜色。
     *
     * 固定白色，不跟主题：弹幕压在视频画面上，用主题色（这里是青绿）会和画面里的
     * 肤色、天空撞成一团，也不符合用户对弹幕的预期。
     */
    val danmuText: Color = Palette.White,

    /**
     * 压在图片 / 视频画面上的内容色。
     *
     * 固定白，不跟明暗：海报、开屏、播放器画面的底色由内容决定，浅色主题下把这些
     * 文字改成深色，遇到暗画面就彻底看不见了。
     */
    val onImage: Color = Palette.White,
    /** 压在图片 / 视频上的玻璃底：半透明白，页面顶部搜索栏这类胶囊用它。 */
    val imageGlass: Color = Palette.White24,
    /** 压在画面上的浅灰文字：90% 阶梯灰（实色），比 [onImage] 低一档，用来区分次级项。 */
    val onImageSubtle: Color = Palette.White90,
    /** 视频上圆形播放控件的半透明底色；不随页面明暗改变。 */
    val playerControlBackground: Color = Palette.ScrimMid,
)

internal fun defaultExtraColors(accent: Color, badge: Color): StarveilExtraColors = StarveilExtraColors(
    brandGold = Palette.Gold400,
    surfaceElevated = Palette.Ink900,
    surfaceCard = Palette.Ink850,
    textMuted = Palette.White70,
    textFaint = Palette.White50,
    divider = Palette.Ink700,
    scrimStrong = Palette.Void,
    heroScrimStart = Palette.ScrimTop,
    heroScrimEnd = Palette.ScrimBottom,
    chipBackground = Palette.Ink800,
    badge = badge,
    accent = accent,
)

internal val DefaultExtraColors = defaultExtraColors(Palette.Aqua500, Palette.Coral500)

/** 旧名字保留，避免既有引用断掉。 */
internal val LightExtraColors = DefaultExtraColors

val LocalStarveilExtraColors = staticCompositionLocalOf { DefaultExtraColors }
