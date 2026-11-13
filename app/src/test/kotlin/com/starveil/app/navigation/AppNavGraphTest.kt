package com.starveil.app.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.starveil.core.designsystem.token.Spacing
import com.starveil.feature.player.PLAYER_ROUTE
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
    fun `dock inset belongs to the page itself, not to the shared content padding`() {
        val direction = LayoutDirection.Ltr
        val scaffoldPadding = PaddingValues(bottom = 116.dp)
        val dockTabs = tabs.map { BottomTabSpec(it, it, it, it, "home") }
        val tabRoutes = dockTabs.map { it.selectedRoute }.toSet()
        val feed = AppShellState(SHORT_PLAY_ROUTE, dockTabs, locked = false, fullscreen = false)
        val episodes = AppShellState("$VERTICAL_PLAY_ROUTE/456/2", dockTabs, locked = false, fullscreen = false)
        val regularTab = AppShellState(HOME_ROUTE, dockTabs, locked = false, fullscreen = false)
        val secondLevel = AppShellState("history", dockTabs, locked = false, fullscreen = false)

        // NavHost 这一层只扣系统安全区：dock 的让位跟着每个页面自己走，
        // 挂在这一层会让点击那一帧就把滑出中的旧页撑满、盖住 dock。
        assertEquals(
            Spacing.none,
            feed.contentPadding(scaffoldPadding, direction, Spacing.none).calculateBottomPadding(),
        )
        assertEquals(
            Spacing.none,
            episodes.contentPadding(scaffoldPadding, direction, Spacing.none).calculateBottomPadding(),
        )
        // 有 dock 的 tab 页必须给 0：页面要铺到屏幕底，dock 才从它底边露出来；
        // 这里留一条导航栏高度，页面下边界正好压住 dock。
        assertEquals(
            Spacing.none,
            regularTab.contentPadding(scaffoldPadding, direction, 24.dp).calculateBottomPadding(),
        )
        // 二级页没有 dock，只吃系统导航栏那一条。
        assertEquals(
            24.dp,
            secondLevel.contentPadding(scaffoldPadding, direction, 24.dp).calculateBottomPadding(),
        )

        // 普通 tab：页面根自己让出 dock 的高度，dock 在下面一层露出来。
        assertEquals(
            regularTab.dockHeight(Spacing.none),
            dockFramePadding(HOME_ROUTE, tabRoutes, regularTab.dockHeight(Spacing.none)),
        )
        // 已让位的页，页面内部不用再留一条。
        assertEquals(
            Spacing.none,
            pageDockInset(HOME_ROUTE, tabRoutes, regularTab.dockHeight(Spacing.none)),
        )
        // 短剧页：视频铺到 dock 后面，页面不让位，让位交给页面内部的列表占位。
        assertEquals(Spacing.none, dockFramePadding(SHORT_PLAY_ROUTE, tabRoutes, 68.dp))
        assertEquals(68.dp, pageDockInset(SHORT_PLAY_ROUTE, tabRoutes, 68.dp))
        // 播放页与二级页：不让位、铺满整屏，直接把 dock 盖住。
        assertEquals(Spacing.none, dockFramePadding("$PLAYER_ROUTE/1/0/0", tabRoutes, 68.dp))
        assertEquals(Spacing.none, dockFramePadding("history", tabRoutes, 68.dp))
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
    fun `dock stays above pages for both directions of a short play transition`() {
        val tabRoutes = tabs.toSet()
        // 短剧页视频铺到 dock 后面：去程与回程都必须压在页面之上。
        // 只判 currentRoute 的话，回程那一帧 dock 掉到页面下方，被退场视频盖住一档动画。
        assertTrue(dockOnTopFor(SHORT_PLAY_ROUTE, HOME_ROUTE, tabRoutes))
        assertTrue(dockOnTopFor(HOME_ROUTE, SHORT_PLAY_ROUTE, tabRoutes))
        assertTrue(dockOnTopFor(SHORT_PLAY_ROUTE, SHORT_PLAY_ROUTE, tabRoutes))
        // 另一端是二级页：那一页铺满整屏，本来就该盖住 dock。
        assertFalse(dockOnTopFor(SHORT_PLAY_ROUTE, "history", tabRoutes))
        // 与短剧无关的切换不压在页面之上。
        assertFalse(dockOnTopFor(HOME_ROUTE, "rank", tabRoutes))
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
