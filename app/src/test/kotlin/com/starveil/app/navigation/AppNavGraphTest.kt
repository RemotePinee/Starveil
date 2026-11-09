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
    fun `dock floats over content so no route loses bottom to it`() {
        val direction = LayoutDirection.Ltr
        val scaffoldPadding = PaddingValues(bottom = 116.dp)
        val dockTabs = tabs.map { BottomTabSpec(it, it, it, it, "home") }
        val feed = AppShellState(SHORT_PLAY_ROUTE, dockTabs, locked = false, fullscreen = false)
        val episodes = AppShellState("$VERTICAL_PLAY_ROUTE/456/2", dockTabs, locked = false, fullscreen = false)
        val regularTab = AppShellState(HOME_ROUTE, dockTabs, locked = false, fullscreen = false)
        val secondLevel = AppShellState("history", dockTabs, locked = false, fullscreen = false)

        // dock 浮在页面之上：底边一律不扣，页面铺到屏幕底，模糊快照才录得到 dock 那块。
        assertEquals(
            Spacing.none,
            feed.contentPadding(scaffoldPadding, direction, Spacing.none).calculateBottomPadding(),
        )
        // 竖屏整页播放没有 dock，保持 0。
        assertEquals(
            Spacing.none,
            episodes.contentPadding(scaffoldPadding, direction, Spacing.none).calculateBottomPadding(),
        )
        assertEquals(
            Spacing.none,
            regularTab.contentPadding(scaffoldPadding, direction, Spacing.none).calculateBottomPadding(),
        )
        // 二级页没有 dock：底边只吃系统导航栏那一档，不再跟 dock 的高度动画一起变
        //（跟着变的那版，从「我的」点进收藏/设置时占位会先偏下再跳回中间）。
        assertEquals(
            24.dp,
            secondLevel.contentPadding(scaffoldPadding, direction, 24.dp).calculateBottomPadding(),
        )
        assertEquals(24.dp, secondLevel.contentPadding(scaffoldPadding, direction, 24.dp).calculateBottomPadding())
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
