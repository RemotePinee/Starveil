package com.starveil.core.designsystem.token

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * 一套皮肤的成品：Material 语义色 + 扩展语义色。
 *
 * 页面不直接使用本类，而是通过 `MaterialTheme.colorScheme` 与 `starveilExtraColors` 取色。
 */
internal class SkinColors(
    val colorScheme: ColorScheme,
    val extra: StarveilExtraColors,
)

/**
 * 一套皮肤的**明暗两份**。
 *
 * 皮肤只决定色相，明暗是另一维：同一套"青"在深色下是亮青、浅色下要压到 700 档，
 * 否则白底上的按钮和文字看不清。所以每套皮肤都要出两份，由 [pick] 按系统/用户偏好取。
 */
internal class SkinTones(
    val dark: SkinColors,
    val light: SkinColors,
) {
    fun pick(darkTheme: Boolean): SkinColors = if (darkTheme) dark else light
}

/**
 * 六套皮肤。
 *
 * 中性色阶与皮肤无关：深色固定 Void → Ink950 → Ink900 → Ink850 → Ink800 → Ink700，
 * 浅色固定 Paper 阶梯。换皮肤只换 primary/secondary 及其 on 色（以及扩展色的
 * accent/badge），背景、卡片、浮层、输入框、抽屉、胶囊、描边都不随皮肤变化——
 * 否则深色下切皮肤整屏会跟着泛色，卡片和页面底分不开。
 *
 * 浅色不是把深色反过来：中性色换成 Paper 阶梯（白 → 浅灰），主色与角标降到 700 档
 * （500 档压在白底上对比不足 3:1），文字用 Ink900/500/300。
 * 浅色的 primaryContainer 仍用主色浅染：它承载"选中态"，不是页面底或卡片底。
 * 海报叠字用的 scrim 两种明暗**都保持深色**：它是压在海报图上的，不是界面底色。
 *
 * 主色取自各套皮肤的原始色：
 * - 默认：青（延续旧品牌）
 * - "1" 石墨灰：原优酷蓝 `#42A1EA`，运营要求换成中性灰——蓝色在浅色主题下会和系统蓝撞
 * - "2" 暖金：琥珀（旧 `shape_bg_orange_to_bsc2` 的暖调）
 * - "3" B 站粉：`#FF679A`（`fragment_homed.xml:134`）
 * - "4" B 站紫：`#7C5CFF`（旧二套，粉紫）
 * - "5" AQC：天蓝 `#03A9F4`
 */
internal object SkinPalettes {

    val default: SkinTones = skinTones(Palette.Aqua500, Palette.Coral500, Palette.Aqua700, Palette.Coral600)

    /** "1" 石墨灰：accent 用中性灰，badge 仍配金，整屏不至于只剩黑白灰。 */
    val graphite: SkinTones = skinTones(Palette.Ink300, Palette.Gold400, Palette.Ink500, Palette.Gold600)

    val katong: SkinTones = skinTones(Palette.Amber500, Palette.Coral500, Palette.Amber700, Palette.Coral600)

    val bilibili: SkinTones = skinTones(Palette.Pink500, Palette.Coral500, Palette.Pink700, Palette.Coral600)

    val bilibili2: SkinTones = skinTones(Palette.Violet500, Palette.Pink500, Palette.Violet700, Palette.Pink700)

    val aqc: SkinTones = skinTones(Palette.Azure500, Palette.Amber500, Palette.Azure700, Palette.Amber700)

    /** 服务端 `app_theme_ui` 原始值 → 皮肤；空值与未知值走默认。 */
    fun of(code: String): SkinTones = when (code.trim()) {
        "1" -> graphite
        "2" -> katong
        "3" -> bilibili
        "4" -> bilibili2
        "5" -> aqc
        else -> default
    }
}

private fun skinTones(
    darkAccent: Color,
    darkBadge: Color,
    lightAccent: Color,
    lightBadge: Color,
): SkinTones = SkinTones(
    dark = SkinColors(
        colorScheme = darkSchemeOf(darkAccent, darkBadge),
        // 深色扩展色与 Material 容器共用同一套中性阶梯
        extra = defaultExtraColors(darkAccent, darkBadge),
    ),
    light = SkinColors(
        colorScheme = lightSchemeOf(lightAccent, lightBadge),
        extra = lightExtraOf(lightAccent, lightBadge),
    ),
)

/**
 * 深色：中性阶梯固定，与皮肤无关，皮肤只决定 primary/secondary 及其 on 色。
 *
 * 各档对应现有 Palette 阶梯：背景 Void → 底 Ink950 → 浮层 Ink900 → 卡片 Ink850
 * → 胶囊 Ink800 → 描边 Ink700。主色容器用中性 Ink700，只把 onPrimaryContainer
 * 留给强调色——选中态仍能看出皮肤，但容器底不再被染色。
 */
private fun darkSchemeOf(accent: Color, badge: Color): ColorScheme = darkColorScheme(
    primary = accent,
    onPrimary = Palette.White,
    primaryContainer = Palette.Ink700,
    onPrimaryContainer = accent,
    secondary = badge,
    onSecondary = Palette.White,
    secondaryContainer = Palette.Ink800,
    onSecondaryContainer = badge,
    background = Palette.Void,
    onBackground = Palette.White,
    surface = Palette.Ink950,
    onSurface = Palette.White,
    surfaceVariant = Palette.Ink850,
    onSurfaceVariant = Palette.White70,
    surfaceDim = Palette.Ink950,
    surfaceBright = Palette.Ink700,
    surfaceContainerLowest = Palette.Void,
    surfaceContainerLow = Palette.Ink900,
    surfaceContainer = Palette.Ink900,
    surfaceContainerHigh = Palette.Ink850,
    surfaceContainerHighest = Palette.Ink800,
    // 抬升叠色也必须中性：默认值是 primary，导航栏/弹层会重新被染成皮肤色
    surfaceTint = Palette.Ink800,
    outline = Palette.Ink700,
    outlineVariant = Palette.Ink800,
    error = Palette.Red500,
    onError = Palette.White,
    scrim = Palette.Void,
    inverseSurface = Palette.White,
    inverseOnSurface = Palette.Ink900,
)

/** 浅色：白底 + 浅灰层次，主色与角标都用深一档，保证 3:1 以上可辨识度。 */
private fun lightSchemeOf(accent: Color, badge: Color): ColorScheme = lightColorScheme(
    primary = accent,
    onPrimary = Palette.White,
    // 浅色的选中态底色仍用主色浅染：这是强调态，不是页面底或卡片底
    primaryContainer = accent.tintOver(Palette.Paper50, CONTAINER_ALPHA),
    onPrimaryContainer = accent,
    secondary = badge,
    onSecondary = Palette.White,
    secondaryContainer = Palette.Paper200,
    onSecondaryContainer = badge,
    background = Palette.Paper50,
    onBackground = Palette.Ink900,
    surface = Palette.Paper50,
    onSurface = Palette.Ink900,
    surfaceVariant = Palette.Paper200,
    onSurfaceVariant = Palette.Ink500,
    surfaceDim = Palette.Paper200,
    surfaceBright = Palette.Paper50,
    surfaceContainerLowest = Palette.Paper50,
    surfaceContainerLow = Palette.Paper100,
    surfaceContainer = Palette.Paper100,
    surfaceContainerHigh = Palette.Paper200,
    surfaceContainerHighest = Palette.Paper200,
    surfaceTint = Palette.Paper200,
    outline = Palette.Paper300,
    outlineVariant = Palette.Paper200,
    error = Palette.Red500,
    onError = Palette.White,
    scrim = Palette.Void,
    inverseSurface = Palette.Ink900,
    inverseOnSurface = Palette.Paper50,
)

private fun lightExtraOf(accent: Color, badge: Color): StarveilExtraColors =
    StarveilExtraColors(
        // 金色在白底上要压深，否则会员标识糊成一片浅黄
        brandGold = Palette.Gold600,
        surfaceElevated = Palette.Paper100,
        surfaceCard = Palette.Paper200,
        textMuted = Palette.Ink500,
        textFaint = Palette.Ink300,
        divider = Palette.Paper300,
        // 遮罩仍是黑的：浮层要压暗下面的内容，浅色主题也不该变白
        scrimStrong = Palette.Void,
        // 海报叠字渐变同理：它压在海报图上，与界面底色无关
        heroScrimStart = Palette.ScrimTop,
        heroScrimEnd = Palette.ScrimBottom,
        chipBackground = Palette.Paper200,
        badge = badge,
        accent = accent,
    )

/** 主色浅染：只给浅色的选中态容器用，深色容器一律走中性阶梯。 */
private fun Color.tintOver(base: Color, alpha: Float): Color = copy(alpha = alpha).compositeOver(base)

private const val CONTAINER_ALPHA = 0.22f
