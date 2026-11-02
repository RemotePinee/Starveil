package com.starveil.core.designsystem.token

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 皮肤强调色：每套皮肤只有一组 accent/badge，深色浅色共用同一组，差别只在 `on` 色。
 *
 * 深色的按钮前景固定白字（深色是主形态，不随皮肤变）；浅色按 WCAG 对比度在白字与 Ink900
 * 之间挑一个，亮色底配深字。
 */
class SkinPaletteAccentTest {

    private val skins: List<Pair<String, SkinTones>> = listOf(
        "默认" to SkinPalettes.of(""),
        "1" to SkinPalettes.of("1"),
        "2" to SkinPalettes.of("2"),
        "3" to SkinPalettes.of("3"),
        "4" to SkinPalettes.of("4"),
        "5" to SkinPalettes.of("5"),
    )

    private val modes = skins.flatMap { (name, tones) ->
        listOf(Triple(name, "dark", tones.dark), Triple(name, "light", tones.light))
    }

    @Test
    fun `accent and badge still carry the skin hue`() {
        assertEquals(6, skins.map { it.second }.distinct().size)
        assertEquals(6, skins.map { it.second.dark.extra.accent }.distinct().size)
        assertEquals(6, skins.map { it.second.light.extra.accent }.distinct().size)
        assertTrue(skins.map { it.second.dark.extra.badge }.distinct().size > 1)
        assertTrue(skins.map { it.second.light.extra.badge }.distinct().size > 1)

        modes.forEach { (name, mode, colors) ->
            assertEquals("$name $mode primary", colors.extra.accent, colors.colorScheme.primary)
            assertEquals("$name $mode secondary", colors.extra.badge, colors.colorScheme.secondary)
            assertNotEquals("$name $mode accent == badge", colors.extra.accent, colors.extra.badge)
        }
    }

    @Test
    fun `light and dark share one set of accent and badge`() {
        skins.forEach { (name, tones) ->
            assertEquals("$name accent", tones.dark.extra.accent, tones.light.extra.accent)
            assertEquals("$name badge", tones.dark.extra.badge, tones.light.extra.badge)
            assertEquals("$name primary", tones.dark.colorScheme.primary, tones.light.colorScheme.primary)
            assertEquals("$name secondary", tones.dark.colorScheme.secondary, tones.light.colorScheme.secondary)
        }
    }

    @Test
    fun `dark buttons keep white content`() {
        skins.forEach { (name, tones) ->
            assertEquals("$name dark onPrimary", Palette.White, tones.dark.colorScheme.onPrimary)
            assertEquals("$name dark onSecondary", Palette.White, tones.dark.colorScheme.onSecondary)
        }
    }

    @Test
    fun `light buttons pick the higher contrast content color`() {
        skins.forEach { (name, tones) ->
            val scheme = tones.light.colorScheme
            assertEquals("$name light onPrimary", expectedOn(scheme.primary), scheme.onPrimary)
            assertEquals("$name light onSecondary", expectedOn(scheme.secondary), scheme.onSecondary)
            // 霓虹紫 #7C5CFF 的两个候选都够不到 4.5:1（白 4.35 / 深 4.31），取高的那个
            assertTrue("$name light onPrimary 对比度", contrast(scheme.onPrimary, scheme.primary) >= 4.3f)
            assertTrue("$name light onSecondary 对比度", contrast(scheme.onSecondary, scheme.secondary) >= 4.3f)
        }
    }

    /** 与 `SkinPalettes` 里的 on 色规则一致：白字与 Ink900 之间取对比度高的那个。 */
    private fun expectedOn(background: Color): Color =
        if (contrast(Palette.White, background) >= contrast(Palette.Ink900, background)) {
            Palette.White
        } else {
            Palette.Ink900
        }

    /** WCAG 2.1 对比度：(较亮相对亮度 + 0.05) / (较暗相对亮度 + 0.05)。 */
    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }
}
