package com.starveil.feature.shortplay

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.bold.SidebarSimple
import com.starveil.core.designsystem.component.StarveilLoading
import com.starveil.core.designsystem.interaction.HapticKind
import com.starveil.core.designsystem.interaction.rememberHaptics
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.ShortPlay
import kotlinx.coroutines.launch

/** 播放器由 app 注入，播放与进入全集的操作由播放器负责。 */
@Composable
fun ShortPlayRoute(
    viewModel: ShortPlayViewModel,
    modifier: Modifier = Modifier,
    playerContent: @Composable (ShortPlay, Boolean) -> Unit = { _, _ -> },
    navigation: ShortPlayNavigation = ShortPlayNavigation(),
    presentation: ShortPlayPresentation = ShortPlayPresentation(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ShortPlayScreen(
        state = state,
        actions = ShortPlayActions(
            onTypeSelect = viewModel::selectType,
            onRetry = viewModel::retry,
            onRefresh = viewModel::refresh,
            onLoadMore = viewModel::loadMore,
            onPageSettled = viewModel::onPageSettled,
        ),
        modifier = modifier,
        playerContent = playerContent,
        navigation = navigation,
        presentation = presentation,
    )
}

data class ShortPlayActions(
    val onTypeSelect: (Int) -> Unit,
    val onRetry: () -> Unit,
    val onRefresh: () -> Unit,
    val onLoadMore: () -> Unit,
    val onPageSettled: (Int) -> Unit,
)

@Composable
fun ShortPlayScreen(
    state: ShortPlayUiState,
    actions: ShortPlayActions,
    modifier: Modifier = Modifier,
    playerContent: @Composable (ShortPlay, Boolean) -> Unit = { _, _ -> },
    navigation: ShortPlayNavigation = ShortPlayNavigation(),
    presentation: ShortPlayPresentation = ShortPlayPresentation(),
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val menuLocked = presentation.seriesExpanded || presentation.interactionLocked
    val drawerClosed = drawer.currentValue == DrawerValue.Closed && drawer.targetValue == DrawerValue.Closed
    LaunchedEffect(menuLocked) {
        if (menuLocked) drawer.close()
    }
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = !drawerClosed && !menuLocked,
        drawerContent = {
            ShortPlayDrawer(state, actions, navigation) { action ->
                scope.launch {
                    drawer.close()
                    action()
                }
            }
        },
        // 关闭的抽屉仍布局在页面左侧；裁掉页外内容，导航横滑时才不会把它带回屏幕。
        modifier = modifier.fillMaxSize().clipToBounds(),
    ) {
        Box(Modifier.fillMaxSize().background(starveilExtraColors.scrimStrong)) {
            when (state) {
                is ShortPlayUiState.Content -> ShortPlayFeed(
                    state, actions, drawerClosed, playerContent, presentation,
                )
                is ShortPlayUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    StarveilLoading()
                }
                is ShortPlayUiState.Empty -> ShortPlayNotice(state.message, "换一批", actions.onRefresh)
                is ShortPlayUiState.Error -> ShortPlayNotice(state.message, "重试", actions.onRetry)
            }
            ShortPlayHeader(
                onMenu = { scope.launch { drawer.open() } },
                locked = menuLocked,
                expansion = presentation.expansion,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun ShortPlayHeader(
    onMenu: () -> Unit,
    locked: Boolean,
    expansion: Float,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth()
            .graphicsLayer {
                val progress = expansion.coerceIn(0f, 1f)
                translationY = -Spacing.rowHeight.toPx() * progress
                alpha = 1f - progress
            }
            .statusBarsPadding()
            .heightIn(min = Spacing.rowHeight)
            .padding(horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShortPlayHeaderButton(PhosphorIcons.Bold.SidebarSimple, "打开短剧菜单", !locked, onMenu)
    }
}

@Composable
private fun ShortPlayHeaderButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESSED_ICON_SCALE else 1f,
        animationSpec = Motion.short,
        label = "shortplay-header-press",
    )
    val haptics = rememberHaptics()
    IconButton(
        onClick = {
            haptics(HapticKind.Tick)
            onClick()
        },
        enabled = enabled,
        // 锁定时连语义一起摘掉：读屏也不再暴露这个入口。
        modifier = Modifier.size(Spacing.rowHeight)
            .then(if (enabled) Modifier else Modifier.clearAndSetSemantics {}),
        interactionSource = interaction,
    ) {
        // 圆底与播放器画面上的圆形控件同款（playerControlBackground）：
        // 图标压在视频亮部时靠它兜住对比度，不再裸着色，也不用页面里编半透明色。
        Box(
            modifier = Modifier
                .size(Spacing.rowHeight - Spacing.xs)
                .clip(AppShape.full)
                .background(starveilExtraColors.playerControlBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = description,
                tint = starveilExtraColors.onImage,
                modifier = Modifier.size(IconSize.md).graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            )
        }
    }
}

@Composable
private fun ShortPlayNotice(message: String, actionLabel: String, onAction: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(Spacing.pageHorizontal), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(message, color = starveilExtraColors.onImage, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

private const val PRESSED_ICON_SCALE = 0.92f
