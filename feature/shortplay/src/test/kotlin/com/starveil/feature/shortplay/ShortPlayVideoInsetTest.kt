package com.starveil.feature.shortplay

import com.starveil.core.designsystem.token.Spacing
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 视频层的底边必须跟着 dock 预留带走：收起时让出整条（那条在页面里是透明的），
 * 整剧态归零（dock 已经整块让开，再让就会在入口条下方留一条底色）。
 */
class ShortPlayVideoInsetTest {

    @Test
    fun `collapsed feed yields the whole dock inset`() {
        val presentation = ShortPlayPresentation(dockInset = Spacing.tabBarHeightIcons)

        assertEquals(Spacing.tabBarHeightIcons, presentation.videoBottomInset())
    }

    @Test
    fun `expanded series gives the bottom edge back`() {
        val presentation = ShortPlayPresentation(
            expansion = 1f,
            dockInset = Spacing.tabBarHeightIcons,
        )

        assertEquals(Spacing.none, presentation.videoBottomInset())
    }

    @Test
    fun `inset follows the expansion progress`() {
        val presentation = ShortPlayPresentation(
            expansion = 0.5f,
            dockInset = Spacing.tabBarHeightIcons,
        )

        assertEquals(Spacing.tabBarHeightIcons / 2f, presentation.videoBottomInset())
    }

    @Test
    fun `no dock means nothing to yield`() {
        assertEquals(Spacing.none, ShortPlayPresentation().videoBottomInset())
    }
}
