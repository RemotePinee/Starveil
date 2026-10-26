package com.starveil.core.designsystem.token

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 皮肤只换强调色，不换容器底色。
 *
 * 深色下的中性阶梯与皮肤无关；浅色沿用 Paper 阶梯。浅色的 `primaryContainer` 仍是
 * 主色浅染的"选中态"填充，不属于页面底/卡片底，所以不参与跨皮肤一致断言。
 */
class SkinPalettesTest {

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

    /** 页面底、卡片、浮层、输入框、导航/抽屉、胶囊、描边。 */
    private val neutralSchemeRoles: List<Pair<String, (ColorScheme) -> Color>> = listOf(
        "background" to { it.background },
        "surface" to { it.surface },
        "surfaceVariant" to { it.surfaceVariant },
        "surfaceDim" to { it.surfaceDim },
        "surfaceBright" to { it.surfaceBright },
        "surfaceContainerLowest" to { it.surfaceContainerLowest },
        "surfaceContainerLow" to { it.surfaceContainerLow },
        "surfaceContainer" to { it.surfaceContainer },
        "surfaceContainerHigh" to { it.surfaceContainerHigh },
        "surfaceContainerHighest" to { it.surfaceContainerHighest },
        "surfaceTint" to { it.surfaceTint },
        "secondaryContainer" to { it.secondaryContainer },
        "outline" to { it.outline },
        "outlineVariant" to { it.outlineVariant },
    )

    private val neutralExtraRoles: List<Pair<String, (StarveilExtraColors) -> Color>> = listOf(
        "surfaceElevated" to { it.surfaceElevated },
        "surfaceCard" to { it.surfaceCard },
        "chipBackground" to { it.chipBackground },
        "divider" to { it.divider },
    )

    @Test
    fun `dark containers are neutral across all six skins`() {
        assertNeutralAcrossSkins("dark") { it.dark }
    }

    @Test
    fun `light containers are neutral across all six skins`() {
        assertNeutralAcrossSkins("light") { it.light }
    }

    @Test
    fun `dark ramp uses the fixed neutral ladder`() {
        skins.forEach { (name, tones) ->
            val scheme = tones.dark.colorScheme
            val extra = tones.dark.extra
            assertEquals("$name background", Palette.Void, scheme.background)
            assertEquals("$name surface", Palette.Ink950, scheme.surface)
            assertEquals("$name surfaceDim", Palette.Ink950, scheme.surfaceDim)
            assertEquals("$name surfaceBright", Palette.Ink700, scheme.surfaceBright)
            assertEquals("$name surfaceContainerLowest", Palette.Void, scheme.surfaceContainerLowest)
            assertEquals("$name surfaceContainerLow", Palette.Ink900, scheme.surfaceContainerLow)
            assertEquals("$name surfaceContainer", Palette.Ink900, scheme.surfaceContainer)
            assertEquals("$name surfaceContainerHigh", Palette.Ink850, scheme.surfaceContainerHigh)
            assertEquals("$name surfaceContainerHighest", Palette.Ink800, scheme.surfaceContainerHighest)
            assertEquals("$name surfaceVariant", Palette.Ink850, scheme.surfaceVariant)
            assertEquals("$name surfaceTint", Palette.Ink800, scheme.surfaceTint)
            assertEquals("$name outline", Palette.Ink700, scheme.outline)
            assertEquals("$name outlineVariant", Palette.Ink800, scheme.outlineVariant)
            assertEquals("$name surfaceElevated", Palette.Ink900, extra.surfaceElevated)
            assertEquals("$name surfaceCard", Palette.Ink850, extra.surfaceCard)
            assertEquals("$name chipBackground", Palette.Ink800, extra.chipBackground)
            assertEquals("$name divider", Palette.Ink700, extra.divider)
        }
    }

    @Test
    fun `light ramp stays on the paper ladder`() {
        skins.forEach { (name, tones) ->
            val scheme = tones.light.colorScheme
            val extra = tones.light.extra
            assertEquals("$name background", Palette.Paper50, scheme.background)
            assertEquals("$name surface", Palette.Paper50, scheme.surface)
            assertEquals("$name surfaceContainer", Palette.Paper100, scheme.surfaceContainer)
            assertEquals("$name surfaceContainerHigh", Palette.Paper200, scheme.surfaceContainerHigh)
            assertEquals("$name surfaceContainerHighest", Palette.Paper200, scheme.surfaceContainerHighest)
            assertEquals("$name outline", Palette.Paper300, scheme.outline)
            assertEquals("$name surfaceElevated", Palette.Paper100, extra.surfaceElevated)
            assertEquals("$name surfaceCard", Palette.Paper200, extra.surfaceCard)
            assertEquals("$name chipBackground", Palette.Paper200, extra.chipBackground)
            assertEquals("$name divider", Palette.Paper300, extra.divider)
        }
    }

    @Test
    fun `dark selection fill is neutral while its content keeps the accent`() {
        skins.forEach { (name, tones) ->
            val scheme = tones.dark.colorScheme
            assertEquals("$name primaryContainer", Palette.Ink700, scheme.primaryContainer)
            assertEquals("$name onPrimaryContainer", tones.dark.extra.accent, scheme.onPrimaryContainer)
            assertEquals("$name secondaryContainer", Palette.Ink800, scheme.secondaryContainer)
            assertEquals("$name onSecondaryContainer", tones.dark.extra.badge, scheme.onSecondaryContainer)
        }
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
            assertEquals("$name $mode onPrimary", Palette.White, colors.colorScheme.onPrimary)
            assertEquals("$name $mode onSecondary", Palette.White, colors.colorScheme.onSecondary)
            assertNotEquals("$name $mode accent == badge", colors.extra.accent, colors.extra.badge)
        }
    }

    @Test
    fun `light and dark use different neutral ladders`() {
        skins.forEach { (name, tones) ->
            neutralSchemeRoles.forEach { (role, read) ->
                assertNotEquals(
                    "$name.$role 深浅相同",
                    read(tones.dark.colorScheme),
                    read(tones.light.colorScheme),
                )
            }
            neutralExtraRoles.forEach { (role, read) ->
                assertNotEquals(
                    "$name.extra.$role 深浅相同",
                    read(tones.dark.extra),
                    read(tones.light.extra),
                )
            }
        }
    }

    @Test
    fun `extra container tokens mirror the material containers`() {
        modes.forEach { (name, mode, colors) ->
            assertEquals(
                "$name $mode surfaceElevated",
                colors.colorScheme.surfaceContainerLow,
                colors.extra.surfaceElevated,
            )
            assertEquals(
                "$name $mode surfaceCard",
                colors.colorScheme.surfaceContainerHigh,
                colors.extra.surfaceCard,
            )
            assertEquals(
                "$name $mode chipBackground",
                colors.colorScheme.surfaceContainerHighest,
                colors.extra.chipBackground,
            )
            assertEquals(
                "$name $mode divider",
                colors.colorScheme.outline,
                colors.extra.divider,
            )
        }
    }

    private fun assertNeutralAcrossSkins(mode: String, pick: (SkinTones) -> SkinColors) {
        val baseline = pick(skins.first().second)
        skins.drop(1).forEach { (name, tones) ->
            val colors = pick(tones)
            neutralSchemeRoles.forEach { (role, read) ->
                assertEquals(
                    "$mode.$role 随皮肤变化了（$name）",
                    read(baseline.colorScheme),
                    read(colors.colorScheme),
                )
            }
            neutralExtraRoles.forEach { (role, read) ->
                assertEquals(
                    "$mode.extra.$role 随皮肤变化了（$name）",
                    read(baseline.extra),
                    read(colors.extra),
                )
            }
        }
    }
}
