package com.starveil.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.LocalStarveilExtraColors
import com.starveil.core.designsystem.token.SkinPalettes
import com.starveil.core.designsystem.token.StarveilExtraColors
import com.starveil.core.designsystem.token.StarveilShapes

/**
 * 主题入口。
 *
 * [skinCode] 是服务端 `app_theme_ui` 的原始字符串 "1".."5"；空值与未知值走默认皮肤。
 * 本模块只依赖 :core:common，故这里只认字符串，不认 :core:model 的 SkinId。
 *
 * [darkTheme] 是另一维：皮肤只定色相，明暗由它决定。默认跟随系统，
 * 用户在设置里强制浅/深时由调用方传进来（见 `AppearanceMode`）。
 *
 * **皮肤只改令牌，不改布局**：颜色差异全部收在 [SkinPalettes] 里，
 * 而"首页/用户中心用哪套布局"由 feature 层用 `Skins.home` / `Skins.userCenter` 表达。
 * 页面禁止直接构造颜色或形状，一律通过 MaterialTheme 与 [starveilExtraColors] 访问。
 */
@Composable
fun StarveilTheme(
    skinCode: String = "",
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val skin = SkinPalettes.of(skinCode).pick(darkTheme)
    CompositionLocalProvider(
        LocalStarveilExtraColors provides skin.extra,
        LocalIsDarkTheme provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = skin.colorScheme,
            typography = StarveilTypography,
            shapes = StarveilShapes,
            content = content,
        )
    }
}

/**
 * 当前是不是深色。
 *
 * 只有"语义色表达不了"的地方才需要它：骨架屏的呼吸透明度、图片上的白色控件、
 * 以及必须在 Compose 之外算颜色的地方。页面取色请继续用主题令牌。
 */
val LocalIsDarkTheme = staticCompositionLocalOf { true }

/**
 * 默认皮肤的语义色映射。其它皮肤见 [SkinPalettes]，这里保留旧名字作为过渡入口。
 */
internal val StarveilLightColors: ColorScheme
    get() = SkinPalettes.default.dark.colorScheme

/**
 * 字体层级。只调整字重与字距，不新增散值字号。
 */
private val StarveilTypography = Typography(
    // 海报叠字 / 页面主标题：字重要够，行高收紧，避免"网站感"
    displaySmall = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp),
    labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 13.sp),
)

/** 扩展语义色访问入口。页面用它取品牌色，不直接引用 Palette。 */
val starveilExtraColors: StarveilExtraColors
    @Composable
    @ReadOnlyComposable
    get() = LocalStarveilExtraColors.current

/** 形状令牌访问入口，避免页面直接构造 RoundedCornerShape。 */
val starveilShapes get() = AppShape
