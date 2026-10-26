package com.starveil.app.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.starveil.core.designsystem.token.Spacing
import com.starveil.feature.player.VERTICAL_PLAY_ROUTE
import com.starveil.feature.shortplay.SHORT_PLAY_ROUTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavGraphTest {
    private val tabs = listOf(HOME_ROUTE, SHORT_PLAY_ROUTE, "rank", "mine")

    @Test
    fun `short play and ordinary pages share opaque sliding transitions`() {
        val routes = tabs + listOf("$VERTICAL_PLAY_ROUTE/30/1", "history", "search")
        routes.forEach { from ->
            routes.filterNot { it == from }.forEach { to ->
                val enter = enterFor(from, to, tabs)
                val exit = exitFor(from, to, tabs)
                assertNotEquals(EnterTransition.None, enter)
                assertNotEquals(ExitTransition.None, exit)
                assertOpaqueSlide(enter)
                assertOpaqueSlide(exit)
            }
        }
        assertOpaqueSlide(pushEnter())
        assertOpaqueSlide(pushExit())
        assertOpaqueSlide(pushPopEnter())
        assertOpaqueSlide(pushPopExit())
        assertOpaqueSlide(searchEnter())
        assertOpaqueSlide(searchExit())
        assertOpaqueSlide(searchPopEnter())
        assertOpaqueSlide(searchPopExit())
    }


    @Test
    fun `short play and episodes draw behind dock while regular tabs preserve inset`() {
        val direction = LayoutDirection.Ltr
        val scaffoldPadding = PaddingValues(bottom = 116.dp)
        val feed = AppShellState(SHORT_PLAY_ROUTE, emptyList(), locked = false, fullscreen = false)
        val episodes = AppShellState("$VERTICAL_PLAY_ROUTE/456/2", emptyList(), locked = false, fullscreen = false)
        val regularTab = AppShellState(HOME_ROUTE, emptyList(), locked = false, fullscreen = false)

        // 短剧页照常扣掉 dock 占位：它用 bleedBottom 自己把那块铺回屏幕底，
        // 这样切 tab 时页面高度不变，贴底那组不会先跳一下（见 ShortPlayDestination）。
        assertEquals(116.dp, feed.contentPadding(scaffoldPadding, direction).calculateBottomPadding())
        // 竖屏整页播放没有 dock，保持 0。
        assertEquals(Spacing.none, episodes.contentPadding(scaffoldPadding, direction).calculateBottomPadding())
        assertEquals(116.dp, regularTab.contentPadding(scaffoldPadding, direction).calculateBottomPadding())
    }

    @Test
    fun `short play dock overlap stays continuous at both animation endpoints`() {
        assertEquals(0f, dockTranslationY(300f, 0f, 3f), 0f)
        assertEquals(0f, dockTranslationY(300f, 0.0001f, 3f), 0.01f)
        assertEquals(147f, dockTranslationY(300f, 0.5f, 3f), 0f)
        assertTrue(dockTranslationY(300f, 0.9999f, 3f) > 299.9f)
        assertEquals(300f, dockTranslationY(300f, 1f, 3f), 0f)
    }

    @Test
    fun `opacity guard detects actual framework fades`() {
        assertTrue(fadeIn().toString(), fadeIn().toString().contains("Fade("))
        assertTrue(fadeOut().toString(), fadeOut().toString().contains("Fade("))
    }
    
    private fun assertOpaqueSlide(transition: Any) {
        val description = transition.toString()
        assertTrue(description, description.contains("Slide("))
        assertFalse(description, description.contains("Fade("))
    }
}
