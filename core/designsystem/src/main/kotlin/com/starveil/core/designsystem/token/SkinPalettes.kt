package com.starveil.core.designsystem.token

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

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
 * 皮肤只决定色相：两份共用同一组 accent/badge，差别只在背景、表面与文字的中性阶梯。
 * 白底上的亮色按钮要配深色字，这一层由 `onColorFor` 按 WCAG 对比度算，所以两份仍各出一套。
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
 * 中性色阶与皮肤无关：深色只留四层可见高度——Ink900（页面底）→ Ink850（浮层/卡片）
 * → Ink800（胶囊/输入）→ Ink700（菜单），再往下是下沉层 Ink950 / Void，
 * 浅色固定 Paper 阶梯。换皮肤只换 primary/secondary 及其 on 色（以及扩展色的
 * accent/badge），背景、卡片、浮层、输入框、抽屉、胶囊、描边都不随皮肤变化——
 * 否则深色下切皮肤整屏会跟着泛色，卡片和页面底分不开。
 *
 * 每套皮肤只有**一组** accent/badge（深色那组），浅色直接复用，色相不再分深浅两套。
 * 浅色只换中性阶梯（Paper 白 → 浅灰）与文字色（Ink900/500/300）；按钮上的前景色由
 * `onColorFor` 按 WCAG 对比度挑：亮底配深字、暗底配白字。
 * 浅色的 primaryContainer 仍用主色浅染：它承载"选中态"，不是页面底或卡片底。
 * 海报叠字用的 scrim 两种明暗**都保持深色**：它是压在海报图上的，不是界面底色。
 *
 * 主色取自各套皮肤的原始色：
 * - 默认：青（延续旧品牌）
 * - "1" 石墨灰：原优酷蓝 `#42A1EA`，运营要求换成中性灰——蓝色在浅色主题下会和系统蓝撞
 * - "2" 暖金：琥珀（旧 `shape_bg_orange_to_bsc2` 的暖调）
 * - "3" B 站粉：`#FF679A`（`fragment_homed.xml:134`）
 * - "4" 霓虹紫：`#7C5CFF`（旧二套，粉紫；**不是** B 站的颜色）
 * - "5" AQC：天蓝 `#03A9F4`
 */
internal object SkinPalettes {

    val default: SkinTones = skinTones(Palette.Aqua500, Palette.Coral500)

    /** "1" 石墨灰：accent 用中性灰，badge 仍配金，整屏不至于只剩黑白灰。 */
    val graphite: SkinTones = skinTones(Palette.Ink300, Palette.Gold400)

    val katong: SkinTones = skinTones(Palette.Amber500, Palette.Coral500)

    /** "3" B 站粉：`#FF679A`。 */
    val bilibili: SkinTones = skinTones(Palette.Pink500, Palette.Coral500)

    /** "4" 霓虹紫：主色紫 `#7C5CFF`，角标配粉 `#FF679A`。 */
    val neonViolet: SkinTones = skinTones(Palette.Violet500, Palette.Pink500)

    val aqc: SkinTones = skinTones(Palette.Azure500, Palette.Amber500)

    /** 服务端 `app_theme_ui` 原始值 → 皮肤；空值与未知值走默认。 */
    fun of(code: String): SkinTones = when (code.trim()) {
        "1" -> graphite
        "2" -> katong
        "3" -> bilibili
        "4" -> neonViolet
        "5" -> aqc
        else -> default
    }
}

private fun skinTones(accent: Color, badge: Color): SkinTones = SkinTones(
    dark = SkinColors(
        colorScheme = darkSchemeOf(accent, badge),
        // 深色扩展色与 Material 容器共用同一套中性阶梯
        extra = defaultExtraColors(accent, badge),
    ),
    // 浅色复用同一组色相，只换中性阶梯与 on 色
    light = SkinColors(
        colorScheme = lightSchemeOf(accent, badge),
        extra = lightExtraOf(accent, badge),
    ),
)

/**
 * 深色：中性阶梯固定，与皮肤无关，皮肤只决定 primary/secondary 及其 on 色。
 *
 * 扁平化删掉了所有阴影，层级只剩色阶。四层可见高度，越往上越亮，相邻至少差 8 个 0-255 色阶
 * （括号里是 R 通道值与相对下一档的差值，由 SkinPalettesTest 断言）：
 * - 页面底 / surface：Ink950 `#0B0C0F`（R 11，Material 3 里 background 与 surface 同色）
 * - 卡片 / 导航栏：Ink800 `#1C1D23`（R 28，+17——上一次卡片 `#16171C` 只差 5 个色阶，
 *   深色下和页面底糊成一片，这一档就是那次问题的修复点）
 * - 弹层 / Dialog：Ink700 `#26272F`（R 38，+10）
 * - 菜单 / 胶囊 / 选中态 / 分隔线：InkMenu `#2F3038`（R 47，+9）
 * 再往下是下沉面：Void / Ink900 只用于视频留边这类"凹陷"，不参与分层。
 * 主色容器用中性 InkMenu（选中态落在最亮一档，压在弹层上也看得出），
 * 只把 onPrimaryContainer 留给强调色——选中态仍能看出皮肤，但容器底不再被染色。
 */
private fun darkSchemeOf(accent: Color, badge: Color): ColorScheme = darkColorScheme(
    primary = accent,
    onPrimary = Palette.White,
    primaryContainer = Palette.InkMenu,
    onPrimaryContainer = accent,
    secondary = badge,
    onSecondary = Palette.White,
    secondaryContainer = Palette.InkMenu,
    onSecondaryContainer = badge,
    background = Palette.Ink950,
    onBackground = Palette.White,
    surface = Palette.Ink950,
    onSurface = Palette.White,
    surfaceVariant = Palette.Ink850,
    onSurfaceVariant = Palette.White70,
    surfaceDim = Palette.Void,
    surfaceBright = Palette.Ink600,
    surfaceContainerLowest = Palette.Void,
    surfaceContainerLow = Palette.Ink850,
    surfaceContainer = Palette.Ink800,
    surfaceContainerHigh = Palette.Ink700,
    surfaceContainerHighest = Palette.InkMenu,
    // 抬升叠色也必须中性：默认值是 primary，导航栏/弹层会重新被染成皮肤色
    surfaceTint = Palette.Ink700,
    outline = Palette.Ink600,
    outlineVariant = Palette.InkMenu,
    error = Palette.Red500,
    onError = Palette.White,
    scrim = Palette.Void,
    inverseSurface = Palette.White,
    inverseOnSurface = Palette.Ink900,
)

/** 浅色：白底 + 浅灰层次，色相与深色同一组，前景色按对比度挑，保证 4.3:1 以上可辨识度。 */
private fun lightSchemeOf(accent: Color, badge: Color): ColorScheme = lightColorScheme(
    primary = accent,
    onPrimary = onColorFor(accent),
    // 浅色的选中态底色仍用主色浅染：这是强调态，不是页面底或卡片底
    primaryContainer = accent.tintOver(Palette.Paper50, CONTAINER_ALPHA),
    onPrimaryContainer = accent,
    secondary = badge,
    onSecondary = onColorFor(badge),
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
    surfaceContainerHighest = Palette.Paper300,
    surfaceTint = Palette.Paper200,
    outline = Palette.Paper300,
    outlineVariant = Palette.Paper200,
    error = Palette.Red500,
    onError = Palette.White,
    scrim = Palette.Void,
    inverseSurface = Palette.Ink900,
    inverseOnSurface = Palette.Paper50,
)

/**
 * 亮色底配深字、暗色底配白字：在 [Palette.White] 与 [Palette.Ink900] 里取对比度更高的那个。
 *
 * 浅色主色里 `#FF679A` 对白字只有 2.74:1、`#FFB020` 只有 1.83:1，都不到 WCAG AA 的 4.5:1；
 * 换成 Ink900 后分别是 6.83:1 与 10.23:1。唯一留白字的是霓虹紫 `#7C5CFF`（白字 4.35:1，
 * 深字 4.31:1，两者都够不到 4.5，取高的那个）。
 */
private fun onColorFor(background: Color): Color =
    if (contrastRatio(Palette.White, background) >= contrastRatio(Palette.Ink900, background)) {
        Palette.White
    } else {
        Palette.Ink900
    }

/** WCAG 2.1 对比度：(较亮相对亮度 + 0.05) / (较暗相对亮度 + 0.05)。 */
private fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

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
        // 会员卡浅色模式偏绿一档：同样的墨绿挨着白底会读成近黑，提饱和才显绿。
        // 会员卡本身不跟主题走，所以这里只是给"浅色模式下的会员卡"换一套更绿的渐变。
        memberMossStart = Palette.Moss800,
        memberMossEnd = Palette.Moss850,
        scrimStrong = Palette.Void,
        // 海报叠字渐变同理：它压在海报图上，与界面底色无关
        heroScrimStart = Palette.ScrimTop,
        heroScrimEnd = Palette.ScrimBottom,
        chipBackground = Palette.Paper300,
        badge = badge,
        accent = accent,
    )

/** 主色浅染：只给浅色的选中态容器用，深色容器一律走中性阶梯。 */
private fun Color.tintOver(base: Color, alpha: Float): Color = copy(alpha = alpha).compositeOver(base)

private const val CONTAINER_ALPHA = 0.22f
