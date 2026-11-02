package com.starveil.core.designsystem.token

import androidx.compose.ui.graphics.Color

/**
 * 原色层：只描述"是什么颜色"，业务代码不得直接引用。
 *
 * 全工程唯一允许出现颜色字面量的地方（见 AGENTS.md 第 3 节）。
 * 命名规则：语义 + 明度档位。**暗色是主形态**——影视类 App 的海报在深底上更亮，
 * 也是商业产品（爱奇艺/优酷/B 站/短剧类）的通行做法；浅色只作为个别皮肤备选。
 */
internal object Palette {

    /*
     * 页面底 Ink950 → 卡片 Ink800 → 浮层（Sheet / Dialog）Ink700 → 菜单 / 胶囊 / 选中 InkMenu。
     * R 通道 11 → 28（+17，卡片和页面底融色的修复点）→ 38（+10）→ 47（+9），与 Material 3
     * 官方深色 ramp 同构；"越往上越亮、相邻至少差 8 个色阶"由 SkinPalettesTest 断言。
     */

    // 中性（暗色阶梯）
    val Void = Color(0xFF070809)
    val Ink950 = Color(0xFF0B0C0F)
    val Ink900 = Color(0xFF111216)
    val Ink850 = Color(0xFF16171C)
    val Ink800 = Color(0xFF1C1D23)
    val Ink700 = Color(0xFF26272F)
    /** 深色阶梯最亮的一档：菜单、胶囊、选中态、分隔线共用，压在浮层上仍能分辨。 */
    val InkMenu = Color(0xFF2F3038)
    val Ink600 = Color(0xFF34353E)
    val Ink500 = Color(0xFF5A5C66)
    val Ink300 = Color(0xFF9A9CA6)
    val Ink100 = Color(0xFFE3E8EF)
    val Ink50 = Color(0xFFF5F7FA)
    val White = Color(0xFFFFFFFF)
    /** 24% 白：压在图片 / 视频上的玻璃底，透出下面的底色而不是盖住它。 */
    val White24 = Color(0x3DFFFFFF)

    // 浅色阶梯：浅色主题的中性色，与上面的 Ink 阶梯对称（越往下越深）。
    // 浅色下的"层次"靠 Paper 由浅到深，正文文字直接用 Ink900 / Ink500 / Ink300。
    val Paper50 = Color(0xFFFFFFFF)
    val Paper100 = Color(0xFFF7F8FA)
    val Paper200 = Color(0xFFEFF1F5)
    val Paper300 = Color(0xFFE4E7EE)

    // 文字档位
    val White90 = Color(0xFFE9E9EE)
    val White70 = Color(0xFFB9BAC2)
    val White50 = Color(0xFF8B8C95)

    // 品牌主色（青，延续旧品牌）
    val Aqua500 = Color(0xFF25CEE8)
    val Aqua200 = Color(0xFF9BE8F5)

    // 强调
    val Gold600 = Color(0xFFB98A2E)
    val Gold400 = Color(0xFFE0B354)
    /** 会员卡文字色：比 [Gold400] 暖一档、偏橙，压在深棕底图上不发灰。 */
    val Gold350 = Color(0xFFF0A94F)
    /** 会员卡墨绿渐变的两端：左端偏亮，向右压暗，金色文字压上去对比够。 */
    val Moss900 = Color(0xFF24312A)
    /** 浅色模式会员卡的墨绿：比深色版更饱和一档，挨着白底才不会读成近黑。 */
    val Moss800 = Color(0xFF2F4738)
    val Moss850 = Color(0xFF16221B)
    val Moss950 = Color(0xFF101613)
    val Coral500 = Color(0xFFFF4159)
    val Violet500 = Color(0xFF7C5CFF)
    val Amber500 = Color(0xFFFFB020)

    // 皮肤主色：每套皮肤一个色相，避免两套撞色；深浅模式共用同一组，不再分 500/700 两档
    val Azure500 = Color(0xFF03A9F4)
    val Pink500 = Color(0xFFFF679A)
    // 状态
    val Red500 = Color(0xFFE5484D)
    val Green500 = Color(0xFF2F9E6B)

    // 海报叠字渐变：两端口径必须一致，所以放令牌层
    val ScrimTop = Color(0x00070809)
    val ScrimBottom = Color(0xE6070809)
    val ScrimMid = Color(0x66070809)

    // 弹幕色板：发送时转成后端要的 6 位十六进制，同时用于面板上的色块预览
    val DanmuWhite = Color(0xFFFFFFFF)
    val DanmuRed = Color(0xFFFF4159)
    val DanmuOrange = Color(0xFFFF8A00)
    val DanmuYellow = Color(0xFFFFD700)
    val DanmuGreen = Color(0xFF39D98A)
    val DanmuCyan = Color(0xFF25CEE8)
    val DanmuBlue = Color(0xFF4A9BFF)
    val DanmuViolet = Color(0xFF9B6BFF)
    val DanmuPink = Color(0xFFFF6FA8)
    val DanmuGrey = Color(0xFFC9CBD3)

    /** 面板上的色块顺序：白 → 暖 → 冷 → 紫。 */
    val DanmuSwatches = listOf(
        DanmuWhite,
        DanmuRed,
        DanmuOrange,
        DanmuYellow,
        DanmuGreen,
        DanmuCyan,
        DanmuBlue,
        DanmuViolet,
        DanmuPink,
        DanmuGrey,
    )
}
