package com.starveil.feature.shortplay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starveil.core.designsystem.component.StarveilLoading
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.ShortPlay
import com.starveil.core.model.ShortPlayType

/** 播放器由 app 注入，播放与进入全集的操作由播放器负责。 */
@Composable
fun ShortPlayRoute(
    viewModel: ShortPlayViewModel,
    modifier: Modifier = Modifier,
    /** 一屏的播放器内容：(短剧, 本屏可播, 本屏画视频)；第三位只对画面归属的那页为真。 */
    playerContent: @Composable (ShortPlay, Boolean, Boolean) -> Unit = { _, _, _ -> },
    navigation: ShortPlayNavigation = ShortPlayNavigation(),
    presentation: ShortPlayPresentation = ShortPlayPresentation(),
    /** 当前落页被整屏广告盖住：外层据此还掉解码器（不释放播放器）。 */
    onCoveredChange: (Boolean) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    ShortPlayScreen(
        state = state,
        suggestions = suggestions,
        // 顶部横滑条的分类：后台单独配的一小组，与分类页的全量分类不是同一组。
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
        onCoveredChange = onCoveredChange,
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
    /** 一屏的播放器内容：(短剧, 本屏可播, 本屏画视频)；第三位只对画面归属的那页为真。 */
    playerContent: @Composable (ShortPlay, Boolean, Boolean) -> Unit = { _, _, _ -> },
    navigation: ShortPlayNavigation = ShortPlayNavigation(),
    presentation: ShortPlayPresentation = ShortPlayPresentation(),
    /** 当前落页被整屏广告盖住：外层据此还掉解码器（不释放播放器）。 */
    onCoveredChange: (Boolean) -> Unit = {},
) {
    var adVisible by remember { mutableStateOf(false) }
    var searchOverlayVisible by rememberSaveable { mutableStateOf(false) }
    // 全集面板展开 / 开合动画期间：菜单与分类条不可点，避免叠出第二套导航。
    val menuLocked = presentation.seriesExpanded || presentation.interactionLocked
    // 顶部横条与左右翻页共用同一份分类列表：页数 = 分类数，点第几个 chip 就滚到第几页。
    // tabs 来自启动配置，每次重组都会重新读一次 cached()。为 null 的那一刻列表会退回
    // state.types（另一组分类），项数与文案都变了——看起来就是「一级分类消失又出现」。
    // 留一份最近的非空值，避免中间这几帧换数据源。
    val rememberedTabs = remember { mutableStateOf(emptyList<ShortPlayType>()) }
    if (tabs.isNotEmpty()) rememberedTabs.value = tabs
    val categories = tabs.ifEmpty { rememberedTabs.value.ifEmpty { state.types } }
    // 挂 feed 的那一页：滑动全程锁在旧页，松手落页才换——滑过半就换会让左右两页的角色瞬间对调。
    // 顶部分类条的页数与左右翻页共用同一份列表：点第几个 chip 就滚到第几页
    val pager = rememberPagerState(pageCount = { categories.size.coerceAtLeast(1) })
    val ownerPage = rememberCategoryOwnerPage(pager)
    // 分类请求随落页发出（ownerPage 只在滚动结束后变），新分类的 feed 在落页之后组合。
    // 落页到数据回来这一段由占位层兜着：新分类的快照不再先铺成 Content——过期的列表会让
    // 已经空掉的分类先闪一整套浮层，这段时间的画面只有分类名——占位层只画它。
    // 快速连滑不会打乱：VM 里每次 selectType 都 ++generation 并取消上一发，迟到的结果被
    // isCurrentRequest 丢掉，只有最后落点那一类的数据会写进 state。点 chip 也会先调一次，
    // 这里同 id 的调用在 VM 里是空操作。
    LaunchedEffect(ownerPage) {
        // 先复位广告态：上一页的 feed 可能停在"被广告整屏盖住"，别把横滑永久锁死。
        adVisible = false
        categories.getOrNull(ownerPage)?.let { actions.onTypeSelect(it.id) }
    }
    val selectType = rememberTypeSelect(pager, categories, actions.onTypeSelect)
    val pageUi = ShortPlayPageUi(
        state = state,
        actions = actions,
        playerContent = playerContent,
        presentation = presentation,
        onAdVisible = { adVisible = it },
        onCoveredChange = onCoveredChange,
    )
    Box(modifier.fillMaxSize().background(starveilExtraColors.scrimStrong)) {
        ShortPlayCategoryPager(
            pager = pager,
            ownerPage = ownerPage,
            // 里层是竖屏 feed：横滑只在没有手势锁、且当前屏不是广告整屏时开放
            //——强制停留的广告不能靠横滑绕开。
            enabled = !presentation.interactionLocked && !adVisible,
            pages = buildPagerPages(pageUi, categories),
        )
        // 广告屏是全屏素材：顶部整块收掉，而不是留一条半透明菜单在素材上。
        AnimatedVisibility(visible = !adVisible) {
            ShortPlayHeader(
                ui = ShortPlayHeaderUi(
                    // 顶部只用后台单独配的那一小组成；没配才退回全量分类。
                    categories = categories,
                    // 选中态由 pager 位置派生：正在滑到的那一页就是高亮项。
                    selectedTypeId = categories.getOrNull(pager.currentPage)?.id
                        ?: state.selectedTypeId,
                    // 下划线跟手指：连续页位置（当前页 + 页间偏移），不是松手才跳。
                    progress = { pager.currentPage + pager.currentPageOffsetFraction },
                    // 菜单不再是"打开抽屉"，而是进入独立的短剧分类页。
                    onMenu = navigation.onCategories,
                    onTypeSelect = selectType,
                    locked = menuLocked,
                    expansion = presentation.expansion,
                    // 顶部只留图标；点开的是独立窗口里的搜索浮层。
                    search = ShortPlaySearchState(
                        expanded = false,
                        onExpandedChange = { open -> searchOverlayVisible = open },
                        onSubmit = {},
                    ),
                ),
                modifier = Modifier.align(Alignment.TopCenter),
            )
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

/** 一页内容（竖屏 feed 或四种整屏态）的全部输入；打包传入是为了压住参数个数。 */
internal data class ShortPlayPageUi(
    val state: ShortPlayUiState,
    val actions: ShortPlayActions,
    val playerContent: @Composable (ShortPlay, Boolean, Boolean) -> Unit,
    val presentation: ShortPlayPresentation,
    val onAdVisible: (Boolean) -> Unit,
    val onCoveredChange: (Boolean) -> Unit,
)

/** 落在当前页上的内容：竖屏 feed 原样交给它，Loading / 空 / 失败仍是整屏一页。 */
@Composable
internal fun ShortPlayPageContent(ui: ShortPlayPageUi) {
    when (val state = ui.state) {
        is ShortPlayUiState.Content -> ShortPlayFeed(
            state, ui.actions, ui.playerContent, ui.presentation,
            // 广告屏是全屏素材，顶部菜单/分类/搜索要收掉。
            onAdVisible = ui.onAdVisible,
            onCoveredChange = ui.onCoveredChange,
        )
        is ShortPlayUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            StarveilLoading()
        }
        is ShortPlayUiState.Empty -> ShortPlayEmptyNotice(state)
        is ShortPlayUiState.Error -> ShortPlayErrorNotice(state.message, ui.actions.onRetry)
    }
}

/**
 * 横滑 pager 里的两种页：owner 页给 [page]（feed 的全部输入），其余页交给 [placeholder]。
 *
 * 打包传入是为了压住参数个数；placeholder 只画占位，不建第二份列表。
 */
internal data class ShortPlayPagerPages(
    val page: ShortPlayPageUi,
    /** 非 owner 页画什么：该分类的占位（只有分类名与一条细线）。 */
    val placeholder: @Composable (Int) -> Unit,
    /**
     * 这一页该不该把 [page] 的 state 画上屏：state 必须属于本页分类。
     *
     * 切分类落页那一拍 state 还挂在上一类（`isRefreshing` 只打标记、不改分类），这一拍
     * 整页只剩占位层——否则上一类的 feed 连同右栏、入口条会先压在新分类上闪一下。
     */
    val onScreen: (Int) -> Boolean,
)

/** 顶部条的全部输入：分类条 + 菜单/搜索状态；打包传入是为了压住参数个数。 */
internal data class ShortPlayHeaderUi(
    val categories: List<ShortPlayType>,
    val selectedTypeId: Int,
    val onMenu: () -> Unit,
    val onTypeSelect: (Int) -> Unit,
    val locked: Boolean,
    val expansion: Float,
    /** 连续页位置（`pager.currentPage + currentPageOffsetFraction`）：下划线跟手指走。 */
    val progress: (() -> Float)? = null,
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
        // 整页唯一进分类页的入口：36dp 触控区包 24dp 图标，压在视频上也看得清。
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
        // 固定最小高度：切剧时 ui.categories 会有一帧是空列表（分类来自启动配置，
        // 那一帧还没就绪），行高塌到 0、下面的内容整体上移，等它回来又被挤下去——
        // 用户看到的就是「一级分类消失又出现、把下面挤过去」。留住高度就不动了。
        ShortPlayCategoryRow(
            categories = ui.categories,
            selectedTypeId = ui.selectedTypeId,
            enabled = !ui.locked,
            onSelect = ui.onTypeSelect,
            progress = ui.progress,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = Spacing.control)
                .padding(horizontal = Spacing.sm),
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

/**
 * 整屏文案态的公共排版：一列居中，标题与补充之间留一档行距。
 *
 * 空态与失败态共用这一层，两种文案才会落在屏幕上同一个位置——切分类时先空后失败（或反过来）
 * 不会看到文案上下跳。
 */
@Composable
private fun NoticeFrame(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().padding(Spacing.pageHorizontal), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            content = content,
        )
    }
}

/**
 * 空态：只有文案，没有按钮。
 *
 * 这里原来挂着一个「换一批」——分类里本来就一条内容都没有时，再点一次只是把同一张空页
 * 再拉一遍，屏幕上什么都不会变。要换内容得换分类（入口在顶部横滑条）或换关键词，
 * 所以第二行给的是"下一步能做什么"，而不是一个点了没反应的按钮。
 */
@Composable
private fun ShortPlayEmptyNotice(state: ShortPlayUiState.Empty) {
    NoticeFrame {
        Text(
            text = state.message,
            color = starveilExtraColors.onImage,
            style = MaterialTheme.typography.titleLarge,
        )
        if (state.hint.isNotEmpty()) {
            Text(
                text = state.hint,
                color = starveilExtraColors.textMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** 失败态：重试是有效操作（同一个分类下一次请求真可能回来），按钮保留。 */
@Composable
private fun ShortPlayErrorNotice(message: String, onRetry: () -> Unit) {
    NoticeFrame {
        Text(
            text = message,
            color = starveilExtraColors.onImage,
            style = MaterialTheme.typography.titleLarge,
        )
        Button(onClick = onRetry) { Text(text = "重试") }
    }
}
