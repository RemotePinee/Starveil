package com.starveil.app.navigation

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat
import androidx.navigation.NavHostController
import com.starveil.core.designsystem.LocalIsDarkTheme
import com.starveil.core.designsystem.interaction.HapticKind
import com.starveil.core.designsystem.interaction.rememberHaptics
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.designsystem.token.Spacing
import com.starveil.feature.player.VERTICAL_PLAY_ROUTE
import com.starveil.feature.shortplay.SHORT_PLAY_ROUTE

/** 短剧前景在完整 viewport 中避让的实测占位；其它页面已在宿主扣除，不再重复传递。 */
internal val LocalAppContentPadding = compositionLocalOf<PaddingValues> { PaddingValues() }

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
    navController: NavHostController,
    state: AppShellState,
    content: @Composable (PaddingValues) -> Unit,
) {
    AppSystemBars(state)
    val presentation = LocalShortPlayPresentation.current
    val inlineDock = state.currentRoute == SHORT_PLAY_ROUTE && state.isTabPage()
    val contentInsets = if (state.fullscreen) {
        WindowInsets(0, 0, 0, 0)
    } else {
        ScaffoldDefaults.contentWindowInsets
    }
    Scaffold(
        modifier = if (state.locked) Modifier.blockPointers() else Modifier,
        contentWindowInsets = contentInsets,
        // dock 始终由同一个 bottomBar 槽绘制：短剧页只是把它的位移交给展开进度。
        // 换成"overlay + bottomBar"两套宿主时，切 tab 那一帧绘制者变了，
        // dock 会整体上移一个导航栏高度，底下的页面就露出来一条。
        bottomBar = {
            if (inlineDock) {
                InlineShortPlayBottomBar(navController, state, presentation.expansion)
            } else {
                AppBottomBar(navController, state)
            }
        },
        content = { padding ->
            // 切走时路由先变、页面还在转场：沿用上一帧的短剧前景占位，
            // 否则入口条会先跳到底再被滑出，看起来像"那栏先消失"。
            val foregroundPadding = rememberStableForegroundPadding(state)
            CompositionLocalProvider(LocalAppContentPadding provides foregroundPadding) {
                Box(Modifier.fillMaxSize()) { content(padding) }
            }
        },
    )
}

/**
 * 短剧前景的底槽高度：只等于 dock 的固定内容高度。
 *
 * 不读 Scaffold 的 bottom padding，也不叠加导航栏 inset——dock 自己已经含 inset；
 * 切 tab 那一帧 Scaffold 的 bottomBar 已换成目标页的，padding 会突增，
 * 贴底那组（标题 / 右栏 / 观看完整短剧条）就被顶上去，下面留出一条黑带。
 */
@Composable
private fun playerForegroundPadding(state: AppShellState): PaddingValues = when {
    !state.isShortPlayPage() -> PaddingValues()
    state.currentRoute == SHORT_PLAY_ROUTE && state.isTabPage() -> PaddingValues(
        bottom = if (state.showTabIcons) TabBarHeightIcon else TabBarHeightText,
    )
    else -> PaddingValues()
}

/**
 * 短剧页的前景占位在离开路由时保持不变：转场期间页面仍在组合，
 * 立刻换成空 padding 会让入口条先跳到底部再被滑出。
 */
@Composable
private fun rememberStableForegroundPadding(
    state: AppShellState,
): PaddingValues {
    val current = playerForegroundPadding(state)
    val retained = remember { mutableStateOf(current) }
    if (state.isShortPlayPage()) retained.value = current
    return retained.value
}

/** 视频背景固定使用浅色状态栏图标；底栏仍跟随当前应用主题。 */
@Composable
private fun AppSystemBars(state: AppShellState) {
    val window = LocalActivity.current?.window ?: return
    val darkTheme = LocalIsDarkTheme.current
    val videoBackdrop = !state.locked && state.isVideoPage()
    SideEffect {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars =
            !darkTheme && !videoBackdrop && !state.isHomePage()
        controller.isAppearanceLightNavigationBars = !darkTheme && !state.isVerticalPlayer() && !state.fullscreen
    }
}

/** 底栏连续收放；实测高度只推动短剧前景，不能重新缩放视频。 */
@Composable
private fun AppBottomBar(navController: NavHostController, state: AppShellState) {
    val presentation = LocalShortPlayPresentation.current
    if (state.currentRoute == SHORT_PLAY_ROUTE && presentation.store != null) {
        InlineShortPlayBottomBar(navController, state, presentation.expansion)
        return
    }
    val animate = !state.locked && state.isVideoPage() && !state.fullscreen
    val animation = tween<IntSize>(durationMillis = Motion.MediumMs, easing = Motion.standard)
    AnimatedVisibility(
        visible = state.isTabPage(),
        enter = if (animate) {
            expandVertically(animationSpec = animation, expandFrom = Alignment.Bottom)
        } else EnterTransition.None,
        exit = if (animate) {
            shrinkVertically(animationSpec = animation, shrinkTowards = Alignment.Bottom)
        } else ExitTransition.None,
    ) {
        val selectedRoute = if (state.isVerticalPlayer()) SHORT_PLAY_ROUTE else state.currentRoute
        BottomBar(navController, selectedRoute, state.tabs, state.showTabIcons)
    }
}

/**
 * 短剧 dock 覆盖全高内容；移动时只裁 dock 自身，不裁播放器 viewport。
 *
 * dock 不在侧栏打开时消失：它一旦透明，露出来的是页面底色，底栏位置会变成一条
 * 黑带。侧栏自己让出 dock 高度（`ShortPlayPresentation.dockInset`），两边都不切。
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
                translationY = dockTranslationY(size.height, expansion, 1.dp.toPx())
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
) {
    fun isVerticalPlayer(): Boolean = currentRoute?.startsWith("$VERTICAL_PLAY_ROUTE/") == true

    fun isShortPlayPage(): Boolean = currentRoute == SHORT_PLAY_ROUTE || isVerticalPlayer()

    fun isVideoPage(): Boolean = fullscreen || isShortPlayPage()

    /** 首页：顶部铺着轮播的同图底色，状态栏图标固定白，避免压在浅色图上读不出来。 */
    fun isHomePage(): Boolean = currentRoute == HOME_ROUTE

    fun contentPadding(
        padding: PaddingValues,
        direction: LayoutDirection,
    ): PaddingValues = PaddingValues(
        start = padding.calculateStartPadding(direction),
        end = padding.calculateEndPadding(direction),
        // 短剧页也照常扣 dock 占位：它自己会把这块铺回去（见 LocalHostBottomInset）。
        // 按路由瞬时归零的话，切 tab 的第一帧页面就矮一截，贴底那组会先往上跳。
        bottom = if (fullscreen || isVerticalPlayer()) Spacing.none else padding.calculateBottomPadding(),
    )

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
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(if (showIcons) TabBarHeightIcon else TabBarHeightText),
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
            val onClick: () -> Unit = {
                // 已经在当前页：不导航、不震动、不出涟漪，点了等于没点
                if (!selected) {
                    haptics(HapticKind.Tick)
                    navController.navigateSingleTop(tab.route)
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
                    modifier = Modifier.size(TabIconSize),
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
private val TabIconSize: Dp = 22.dp

/** 图标模式 64dp（图标 22 + 行高 21 + 间距）；纯文字没有图标占位，收到 44dp。 */
private val TabBarHeightIcon: Dp = 64.dp
private val TabBarHeightText: Dp = 44.dp

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
