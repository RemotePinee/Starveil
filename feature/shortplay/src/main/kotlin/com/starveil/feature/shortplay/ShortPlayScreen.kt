package com.starveil.feature.shortplay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.bold.List
import com.starveil.core.designsystem.component.StarveilLoading
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.starveil.core.designsystem.interaction.HapticKind
import com.starveil.core.designsystem.interaction.rememberHaptics
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.model.ShortPlay
import com.starveil.core.model.ShortPlayType
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
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    ShortPlayScreen(
        state = state,
        suggestions = suggestions,
        // 顶部横滑条的分类：后台单独配的一小组，与抽屉的全量分类不是同一组。
        tabs = viewModel.tabs,
        actions = ShortPlayActions(
            onTypeSelect = viewModel::selectType,
            onRetry = viewModel::retry,
            onRefresh = viewModel::refresh,
            onLoadMore = viewModel::loadMore,
            onPageSettled = viewModel::onPageSettled,
            onSearch = viewModel::suggest,
            onSearchClear = viewModel::clearSearch,
            onPickResult = viewModel::playFromSearch,
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
    val onSearch: (String) -> Unit,
    val onSearchClear: () -> Unit,
    val onPickResult: (ShortPlay) -> Unit,
)

@Composable
fun ShortPlayScreen(
    state: ShortPlayUiState,
    actions: ShortPlayActions,
    modifier: Modifier = Modifier,
    suggestions: List<ShortPlay> = emptyList(),
    /** 顶部横滑条的分类（后台单独配的一小组）；空则由顶部退回 [state] 的全量分类。 */
    tabs: List<ShortPlayType> = emptyList(),
    playerContent: @Composable (ShortPlay, Boolean) -> Unit = { _, _ -> },
    navigation: ShortPlayNavigation = ShortPlayNavigation(),
    presentation: ShortPlayPresentation = ShortPlayPresentation(),
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var searchOverlayVisible by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val menuLocked = presentation.seriesExpanded || presentation.interactionLocked
    val drawerClosed = drawer.isClosed()
    LaunchedEffect(menuLocked) {
        if (menuLocked) drawer.close()
    }
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = !drawerClosed && !menuLocked,
        drawerContent = {
            ShortPlayDrawer(
                state = state,
                actions = actions,
                navigation = navigation,
                dockInset = presentation.dockInset,
                onClose = { scope.launch { drawer.close() } },
                // 抽屉里的搜索入口：先关抽屉，再让顶部胶囊展开并聚焦。
                onSearchClick = { searchOverlayVisible = true },
                onAction = { action ->
                    scope.launch {
                        drawer.close()
                        action()
                    }
                },
            )
        },
        // 关闭的抽屉仍布局在页面左侧；裁掉页外内容，导航横滑时才不会把它带回屏幕。
        modifier = modifier.fillMaxSize().clipToBounds(),
    ) {
        var adVisible by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(starveilExtraColors.scrimStrong)) {
            when (state) {
                is ShortPlayUiState.Content -> ShortPlayFeed(
                    state, actions, drawerClosed, playerContent, presentation,
                    // 广告屏是全屏素材，顶部菜单/分类/搜索要收掉。
                    onAdVisible = { adVisible = it },
                )
                is ShortPlayUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    StarveilLoading()
                }
                is ShortPlayUiState.Empty -> ShortPlayNotice(state.message, "换一批", actions.onRefresh)
                is ShortPlayUiState.Error -> ShortPlayNotice(state.message, "重试", actions.onRetry)
            }
            // 广告屏是全屏素材：顶部整块收掉，而不是留一条半透明菜单在素材上。
            AnimatedVisibility(visible = !adVisible) {
                ShortPlayHeader(
                    ui = ShortPlayHeaderUi(
                        // 顶部只用后台单独配的那一小组成；没配才退回抽屉的全量分类。
                        categories = tabs.ifEmpty { state.types },
                        selectedTypeId = state.selectedTypeId,
                        onMenu = { scope.launch { drawer.open() } },
                        onTypeSelect = actions.onTypeSelect,
                        locked = menuLocked,
                        expansion = presentation.expansion,
                        // 顶部只留图标；点开的是独立窗口里的搜索浮层。
                        search = ShortPlaySearchState(
                            expanded = false,
                            onExpandedChange = { open -> searchOverlayVisible = open && drawerClosed },
                            onSubmit = {},
                        ),
                    ),
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
        ShortPlaySearchOverlay(
            visible = searchOverlayVisible,
            candidates = suggestions,
            onQueryChange = actions.onSearch,
            onPick = { play ->
                actions.onPickResult(play)
                searchOverlayVisible = false
            },
            onDismiss = {
                actions.onSearchClear()
                searchOverlayVisible = false
            },
        )
    }
}

/** 顶部条的全部输入：分类条 + 菜单/搜索状态；打包传入是为了压住参数个数。 */
internal data class ShortPlayHeaderUi(
    val categories: List<ShortPlayType>,
    val selectedTypeId: Int,
    val onMenu: () -> Unit,
    val onTypeSelect: (Int) -> Unit,
    val locked: Boolean,
    val expansion: Float,
    val search: ShortPlaySearchState = ShortPlaySearchState(),
)

/**
 * 顶部：菜单图标 + 横滑分类条 + 搜索图标。
 *
 * 分类条取代了原来的「短剧 / 当前分类」两行标题：横滑条本身已经把当前分类写在屏幕上，
 * 再叠两行标题只会在小屏上把首帧挤掉半屏。展开态整条淡出上移的行为保持不变。
 */
@Composable
private fun ShortPlayHeader(ui: ShortPlayHeaderUi, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth()
            .graphicsLayer {
                val progress = ui.expansion.coerceIn(0f, 1f)
                translationY = -Spacing.rowHeight.toPx() * progress
                alpha = 1f - progress
            }
            .statusBarsPadding()
            .heightIn(min = Spacing.rowHeight)
            // 整体下移一档：贴住状态栏会让图标和分类条显得被切掉一截。
            .padding(start = Spacing.sm, top = Spacing.sm, end = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 整页唯一进抽屉的入口：36dp 触控区包 24dp 图标，压在视频上也看得清。
        Box(
            modifier = Modifier
                .size(IconSize.xl)
                .clip(AppShape.small)
                .clickable(enabled = !ui.locked, onClickLabel = "打开短剧分类", onClick = ui.onMenu),
            contentAlignment = Alignment.Center,
        ) {
            // 红果那种「上长、中短、下长」的三条圆头横线：图标库里的 List/Rows 都是
            // 等长三条，形状对不上，直接画。
            val lineColor = starveilExtraColors.onImage
            Canvas(Modifier.size(IconSize.md + Spacing.xxs)) {
                // 线宽 3dp、线长只占 72%，端点圆头：太宽会读成"横条"，不像菜单。
                val stroke = (Spacing.xxs + Spacing.hairline).toPx()
                val long = size.width * 0.72f
                val short = size.width * 0.44f
                val ys = listOf(size.height * 0.22f, size.height * 0.5f, size.height * 0.78f)
                val widths = listOf(long, short, long)
                ys.forEachIndexed { index, y ->
                    drawLine(
                        color = lineColor,
                        start = Offset(0f, y),
                        end = Offset(widths[index], y),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
        ShortPlayCategoryRow(
            categories = ui.categories,
            selectedTypeId = ui.selectedTypeId,
            enabled = !ui.locked,
            onSelect = ui.onTypeSelect,
            modifier = Modifier.weight(1f).padding(horizontal = Spacing.sm),
        )
        // 展开走弹簧：胶囊宽度连续变化，中间任何一帧都是药丸。
        val barWidth by animateDpAsState(
            targetValue = if (ui.search.expanded) SearchBarExpandedWidth else SearchBarHeight,
            animationSpec = spring(dampingRatio = Motion.SpringDampingRatio, stiffness = Motion.SpringStiffness),
            label = "shortplay-search-width",
        )
        ShortPlaySearchBar(
            expanded = ui.search.expanded,
            onExpandedChange = ui.search.onExpandedChange,
            onSubmit = ui.search.onSubmit,
            modifier = Modifier.width(barWidth),
        )
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


/** 抽屉是否完全关闭：抽成函数是为了把两个 && 从 `ShortPlayScreen` 的圈复杂度里挪出去。 */
private fun DrawerState.isClosed(): Boolean =
    currentValue == DrawerValue.Closed && targetValue == DrawerValue.Closed
