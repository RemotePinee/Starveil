package com.starveil.app.navigation

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat
import androidx.navigation.NavHostController
import com.starveil.core.designsystem.LocalIsDarkTheme
import com.starveil.core.designsystem.LocalDockInset
import com.starveil.core.designsystem.interaction.HapticKind
import com.starveil.core.designsystem.interaction.rememberHaptics
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.designsystem.LocalLoginPrompt
import com.starveil.core.designsystem.LoginReason
import com.starveil.feature.player.PLAYER_ROUTE
import com.starveil.feature.player.VERTICAL_PLAY_ROUTE
import com.starveil.feature.shortplay.SHORT_PLAY_ROUTE
import com.starveil.feature.user.UserNavigation

/** 短剧前景在完整 viewport 中避让的实测占位；其它页面已在宿主扣除，不再重复传递。 */
internal val LocalAppContentPadding = compositionLocalOf<PaddingValues> { PaddingValues() }

/** 当前有没有登录会话。底栏的会员 tab 要在导航前就问一句，不再靠页面自己拦。 */
internal val LocalLoggedIn = compositionLocalOf { false }

/**
 * 宿主已经从 NavHost 底边扣掉的 dock 占位。
 *
 * 短剧页要把这块钱铺回来（视频落在 dock 之下），否则切 tab 那一帧页面会突然变矮，
 * 贴底那组（标题 / 右栏 / 观看完整短剧条）整体上跳。
 */
internal val LocalHostBottomInset = compositionLocalOf { Spacing.none }

/**
 * 应用骨架：Scaffold + 底栏 + 锁屏期间的触摸拦截。
 *
 * 从 `StarveilNavHost` 搬出来的是"屏幕怎么摆"这件事——它和启动配置、导航图都没有关系。
 * 那个宿主函数的圈复杂度一直往上涨，涨的正是这类与本函数职责无关的分支。
 * 行为与搬之前完全一致：inset 判定、底栏显示条件、触摸拦截、选中态涟漪都原样保留。
 */
@Composable
internal fun AppScaffold(
    state: AppShellState,
    content: @Composable (PaddingValues) -> Unit,
) {
    AppSystemBars(state)
    // 横屏时系统导航栏贴在侧边：systemBars 的水平 inset 会把整棵内容子树往里推，
    // 左侧留出一条没人绘制的空白。页面只吃垂直方向的系统栏，侧边交由内容自己铺满。
    val contentInsets = if (state.fullscreen) {
        WindowInsets(0, 0, 0, 0)
    } else {
        ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Vertical)
    }
    val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Scaffold(
        modifier = if (state.locked) Modifier.blockPointers() else Modifier,
        contentWindowInsets = contentInsets,
        // dock 不再占 bottomBar 槽：它和页面在同一层的前后两层里（见 StarveilNavHost），
        // 页面自己让位它就露出来，页面铺满就被盖住——不需要它自己做显隐。
        content = { padding ->
            // 短剧前景的底槽按 dock 的真实高度给：dock 挪出 Scaffold 之后
            // `padding.calculateBottomPadding()` 会变成 0，入口条会整体下移一截。
            val foregroundPadding = rememberStableForegroundPadding(
                state = state,
                dockInset = dockHeight(state.showTabIcons, systemBottom),
            )
            CompositionLocalProvider(
                LocalAppContentPadding provides foregroundPadding,
            ) {
                Box(Modifier.fillMaxSize()) { content(padding) }
            }
        },
    )
}

/**
 * 短剧前景的底槽高度：dock 的实际高度。
 *
 * dock 挪出 Scaffold 的 bottomBar 槽之后，`padding.calculateBottomPadding()` 变成 0，
 * 短剧入口条会整体下移一个 dock 高度。这里显式喂 dock 高度，占位与挪之前一致：
 * 页面前景底边与 dock 上沿同源，差一条就是入口条被压住，差多条就是下面留一块纯色。
 */
@Composable
private fun playerForegroundPadding(state: AppShellState, dockInset: Dp): PaddingValues =
    if (state.isShortPlayPage()) {
        PaddingValues(bottom = dockInset)
    } else {
        PaddingValues()
    }

/**
 * 短剧页的前景占位在离开路由时保持不变：转场期间页面仍在组合，
 * 立刻换成空 padding 会让入口条先跳到底部再被滑出。
 */
@Composable
private fun rememberStableForegroundPadding(
    state: AppShellState,
    dockInset: Dp,
): PaddingValues {
    val current = playerForegroundPadding(state, dockInset)
    val retained = remember { mutableStateOf(current) }
    if (state.isShortPlayPage()) retained.value = current
    return retained.value
}

/** 视频背景固定使用浅色状态栏图标；首页自己按顶栏进度切（见 `HomeTopBar.SyncStatusBarIcons`）。 */
@Composable
private fun AppSystemBars(state: AppShellState) {
    val window = LocalActivity.current?.window ?: return
    val darkTheme = LocalIsDarkTheme.current
    val videoBackdrop = !state.locked && state.isVideoPage()
    SideEffect {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        // 首页顶部是剧照、往下滚又是页面底色，两种底色两种图标色，交给首页按进度自己切
        if (!state.isHomePage()) {
            controller.isAppearanceLightStatusBars = !darkTheme && !videoBackdrop
        }
        controller.isAppearanceLightNavigationBars = !darkTheme && !state.isVerticalPlayer() && !state.fullscreen
    }
}

/**
 * 底栏本身。
 *
 * 它不做显隐：tab 页由页面自己让位、它在下面一层露出来；播放页与二级页铺满整屏，
 * 直接把它盖住。所以进播放页看不到"dock 先消失"这一帧。
 */
@Composable
internal fun AppBottomBar(navController: NavHostController, state: AppShellState) {
    val presentation = LocalShortPlayPresentation.current
    if (state.currentRoute == SHORT_PLAY_ROUTE && presentation.store != null) {
        InlineShortPlayBottomBar(navController, state, presentation.expansion)
        return
    }
    val selectedRoute = if (state.isVerticalPlayer()) SHORT_PLAY_ROUTE else state.currentRoute
    BottomBar(navController, selectedRoute, state.tabs, state.showTabIcons)
}

/**
 * 短剧 dock 覆盖全高内容；移动时只裁 dock 自身，不裁播放器 viewport。
 *
 * dock 是始终在位的入口条：它一旦透明，露出来的是页面底色，底栏位置会变成一条
 * 黑带。短剧页用 `bleedBottom` 把 dock 占位自己补回来，两边都不切。
 */
@Composable
private fun InlineShortPlayBottomBar(
    nav: NavHostController,
    state: AppShellState,
    expansion: Float,
) {
    if (!state.isTabPage()) return
    Box(Modifier.fillMaxWidth().clipToBounds()) {
        BottomBar(
            navController = nav,
            currentRoute = state.currentRoute,
            tabs = state.tabs,
            showIcons = state.showTabIcons,
            // 小数像素边界会露出 1px 底色；重叠量在两端归零，避免 dock 收尾时跳 1dp。
            modifier = Modifier.graphicsLayer {
                translationY = dockTranslationY(size.height, expansion, Spacing.hairline.toPx())
            },
        )
    }
}

/** 只在动画内部重叠底色；端点保持原位，避免 preview/expanded 切换的最后一帧跳动。 */
internal fun dockTranslationY(heightPx: Float, expansion: Float, overlapPx: Float): Float {
    val offset = heightPx * expansion
    val overlap = minOf(overlapPx, offset, heightPx - offset).coerceAtLeast(0f)
    return offset - overlap
}

/** 骨架的输入。收成一个对象只为压住参数个数（detekt `LongParameterList` 阈值 5）。 */
internal class AppShellState(
    val currentRoute: String?,
    val tabs: List<BottomTabSpec>,
    val locked: Boolean,
    val fullscreen: Boolean,
    /** 底栏是否画图标；关闭后只画文字（后台「底部菜单 → 底栏显示图标」）。 */
    val showTabIcons: Boolean = true,
    /**
     * 转场结束之后才更新的路由：dock 显隐与内容区底边只认它。
     *
     * 直接用 [currentRoute] 的话，转场第一帧就把 dock 判成"该消失"，
     * 已进入但还在滑的旧页面高度随之变化，被顶上去、底部露黑。
     * 页面高亮之类仍用 [currentRoute]，那里需要实时。
     */
    val settledRoute: String? = currentRoute,
) {
    fun isVerticalPlayer(): Boolean = currentRoute?.startsWith("$VERTICAL_PLAY_ROUTE/") == true

    fun isShortPlayPage(): Boolean = currentRoute == SHORT_PLAY_ROUTE || isVerticalPlayer()

    fun isVideoPage(): Boolean = fullscreen || isShortPlayPage()

    /** 首页：顶部铺着轮播的同图底色，状态栏图标固定白，避免压在浅色图上读不出来。 */
    fun isHomePage(): Boolean = currentRoute == HOME_ROUTE


    /**
     * 当前页有没有 dock 浮在上面。
     *
     * 只认底栏自己那几栏：[isTabPage] 对历史、搜索、设置这些二级页也返回 true，
     * 而它们根本没有 dock——这类页面的底边仍要吃系统导航栏，否则最后一行贴着导航栏。
     *
     * 用 [currentRoute] 而不是 [settledRoute]：dock 属于页面本身，切到没有 dock 的页面
     * 那一帧就不该画；等转场结束再收会变成"进去以后才消失"。
     */
    fun hasDock(): Boolean = isTabPage() && tabs.any { it.selectedRoute == currentRoute }

    /**
     * NavHost 要扣掉的安全区。
     *
     * @param systemBottom 系统导航栏那一条（由调用方从 WindowInsets 取）
     *
     * 二级页以前直接拿 Scaffold 的 bottom padding，而那个值跟着 dock 的进出动画在变：
     * 从「我的」点进收藏/设置的那一刻，dock 正在收起，页面可用高度一路缩，
     * 居中的占位就"先偏下、再跳回中间"。这里改成只吃系统导航栏——跟 dock 动画无关。
     */
    fun contentPadding(
        padding: PaddingValues,
        direction: LayoutDirection,
        systemBottom: Dp,
    ): PaddingValues = PaddingValues(
        start = padding.calculateStartPadding(direction),
        end = padding.calculateEndPadding(direction),
        // 顶部一律不在这里裁：NavHost 一旦被 top padding 裁切，页面根就缩到状态栏
        // 下沿，转场时源页往左滑走、状态栏那条没有任何页面在画它，露出 Scaffold 底色
        // ——就是"切换页面时顶部先闪一条黑条"。
        // 所以页面根永远铺满，避让交给各页面的**内容层**（首页/我的本来就这么做，
        // 其余页面用 `statusBarsPadding()`），这样背景跟着整页一起滑，不会断层。
        top = Spacing.none,
        // dock 与页面同层：有 dock 的页面底边一律不扣，页面铺到屏幕底，
        // 由页面自己的 TabDockFrame 让位、dock 从下面露出来；
        // 播放页与二级页不让位，铺满整屏直接把它盖住。
        bottom = contentBottomInset(
            currentRoute = currentRoute,
            hasDock = hasDock(),
            systemBottom = systemBottom,
        ),
    )

    /** dock 占的高度：内容高 + 系统导航栏那一条。 */
    fun dockHeight(systemBottom: Dp): Dp = dockHeight(showTabIcons, systemBottom)

    /** 当前页是不是"该有底栏的 tab 页"。 */
    fun isTabPage(): Boolean =
        !fullscreen && !locked &&
            currentRoute !in FULLSCREEN_FORM_ROUTES &&
            !isVerticalPlayer()
}

@Composable
private fun BottomBar(
    navController: NavHostController,
    currentRoute: String?,
    tabs: List<BottomTabSpec>,
    showIcons: Boolean,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val labelStyle = tabLabelStyle(tabs.size, showIcons)
    Row(
        // 位移动画必须包住底色与内容：挂在背景之后只会移动子元素，留下一条空白底条。
        modifier = modifier
            .fillMaxWidth()
            .background(starveilExtraColors.surfaceElevated)
            // 只要底部那一条：横屏时导航栏在侧边，全方向 padding 会把五个 tab 左右各缩一条。
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .height(if (showIcons) Spacing.tabBarHeightIcons else Spacing.tabBarHeightText),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val selected = currentRoute == tab.selectedRoute
            val visual = TabVisual(
                label = tab.label,
                selected = selected,
                icon = if (showIcons) bottomTabIcon(tab.icon) else null,
                style = labelStyle,
            )
            // CompositionLocal 只能在组合里读：先取出来，点击回调里再用
            val loggedIn = LocalLoggedIn.current
            val prompt = LocalLoginPrompt.current
            val onClick: () -> Unit = {
                // 已经在当前页：不导航、不震动、不出涟漪，点了等于没点
                if (!selected) {
                    // 会员页要先有账号：没登录直接弹登录层，不导航——
                    // 靠页面自己拦会先滑进一个空页再弹回来，看着像抖了一下。
                    if (tab.requiresAccount && !loggedIn) {
                        prompt.show(LoginReason.GENERAL)
                    } else {
                        haptics(HapticKind.Tick)
                        navController.navigateSingleTop(tab.route)
                    }
                }
            }
            val item: @Composable () -> Unit = { TabItem(visual, onClick) }
            if (selected) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) { item() }
            } else {
                item()
            }
        }
    }
}

/** 底栏一个按钮要画什么。收成对象只为压住参数个数（detekt `LongParameterList`）。 */
private class TabVisual(
    val label: String,
    val selected: Boolean,
    /** null = 后台关掉了图标，只画文字。 */
    val icon: ImageVector?,
    val style: TextStyle,
)

@Composable
private fun RowScope.TabItem(visual: TabVisual, onClick: () -> Unit) {
    val color = if (visual.selected) {
        MaterialTheme.colorScheme.onBackground
    } else {
        starveilExtraColors.textMuted
    }
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (visual.icon != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(IconSize.tab),
                )
                Spacer(Modifier.height(Spacing.xxs))
                Text(text = visual.label, style = visual.style, color = color, maxLines = 1)
            }
        } else {
            // 纯文字模式：没有图标占位，文字自己垂直居中，选中靠字重区分
            Text(
                text = visual.label,
                style = visual.style,
                color = color,
                fontWeight = if (visual.selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/**
 * Dock label size follows tab count. Text-only mode gets stronger type because no icon shares emphasis.
 */
@Composable
private fun tabLabelStyle(count: Int, showIcons: Boolean): TextStyle {
    val type = MaterialTheme.typography
    if (!showIcons) return type.titleLarge
    return when {
        count <= 3 -> type.titleMedium
        count == 4 -> type.labelLarge
        else -> type.bodyMedium.copy(fontWeight = FontWeight.Medium)
    }
}

/**
 * 吞掉落在本节点上的所有指针事件。
 *
 * 只挂在锁屏下面的 Scaffold 上：锁屏是兄弟节点，命中测试会先命中它，
 * 所以锁屏内部的按钮/输入框不受影响，而下层的海报、底栏点不动。
 */
private fun Modifier.blockPointers(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}
