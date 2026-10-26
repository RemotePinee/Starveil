package com.starveil.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.starveil.core.designsystem.component.StarveilLoadingScreen
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.designsystem.token.Spacing
import kotlinx.coroutines.delay
import com.starveil.core.model.Vod

/**
 * 片库筛选页入口。
 *
 * 状态来自 [FilterViewModel]；页面不持有 NavController，跳转全部走 [FilterActions]
 * 回调（AGENTS.md 第 8 节）。
 */
@Composable
fun FilterRoute(
    viewModel: FilterViewModel,
    onBack: () -> Unit,
    onVodClick: (Vod) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    FilterScreen(
        state = state,
        actions = FilterActions(
            onSelect = viewModel::onSelect,
            onLoadMore = viewModel::loadMore,
            onRetry = viewModel::retry,
            onBack = onBack,
            onSearch = onSearch,
            onVodClick = onVodClick,
        ),
        modifier = modifier,
    )
}

/**
 * 筛选条固定在顶部，只有海报墙滚动。
 *
 * 筛选条不跟着滚：用户点完一项要立刻看到同一组条件在哪儿（并继续微调），
 * 让它滚走等于每次都得先滑回顶部。空结果时筛选条也必须留着，否则用户改不回条件。
 */
@Composable
fun FilterScreen(
    state: FilterUiState,
    actions: FilterActions,
    modifier: Modifier = Modifier,
) {
    // 进场先亮加载态，等转场稳定后再渲染海报墙：
    // 首屏卡片一上屏就是几十个文本节点 + 二十来个图片请求，压在进场动画上要卡 200ms 以上。
    var stable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(Motion.TabSwitchMs.toLong())
        stable = true
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.statusBarsPadding()) {
            FilterTopBar(onBack = actions.onBack, onSearch = actions.onSearch)
            FilterRows(rows = state.rows, onSelect = actions.onSelect)
        }
        Box(modifier = Modifier.weight(1f)) {
            when {
                // 有内容就不亮加载态：后台刷新、翻页、二次进入都不该再闪一下转圈。
                // 转场还没稳：先亮加载态，卡片等动画跑完再上屏。
                !stable -> StarveilLoadingScreen()

                state.isLoading && state.items.isEmpty() -> StarveilLoadingScreen()

                // 只有"一条都没有 + 请求失败"才是错误态；已经有结果时翻页失败保留列表
                state.error != null && state.items.isEmpty() ->
                    FilterError(message = state.error.orEmpty(), onRetry = actions.onRetry)

                state.isEmpty -> Centered {
                    Text(text = EMPTY_TEXT, color = starveilExtraColors.textMuted)
                }

                else -> FilterGrid(state = state, actions = actions)
            }
        }
    }
}

/** 顶栏：返回 + 标题 + 搜索。搜索是入口不是筛选维度，所以只上报动作。 */
@Composable
private fun FilterTopBar(onBack: () -> Unit, onSearch: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Spacing.rowHeight)
            .padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = PhosphorIcons.Regular.ArrowLeft,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(IconSize.md).mirrorInRtl(),
            )
        }
        Text(
            text = "片库",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSearch) {
            Icon(
                imageVector = PhosphorIcons.Regular.MagnifyingGlass,
                contentDescription = "搜索",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(IconSize.md),
            )
        }
    }
}

@Composable
private fun FilterRows(rows: List<FilterRow>, onSelect: (FilterKind, String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs + Spacing.xxs),
    ) {
        rows.forEach { row -> FilterChipRow(row = row, onSelect = onSelect) }
    }
}


/** 三列海报墙。按列数把列表切成行，复用首页的 [PosterRows]，不再写一套卡片。 */
@Composable
private fun FilterGrid(state: FilterUiState, actions: FilterActions) {
    val listState = rememberLazyListState()
    // 换条件后回到顶部：结果集整批换了，停在原位会看到"剩下半屏都是空的"
    LaunchedEffect(state.selection) { listState.scrollToItem(0) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = Spacing.sm, bottom = Spacing.xl),
        // 海报行之间留一档间距：标题贴着上一行的图，不加间距会看成一整块
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        items(items = state.items.chunked(COLUMNS), key = { row -> row.first().id }) { row ->
            PosterRows(items = row, columns = COLUMNS, onVodClick = actions.onVodClick)
        }
        item(key = FOOTER_KEY) {
            FilterFooter(isLoadingMore = state.isLoadingMore, hasMore = state.hasMore)
        }
    }
    LoadMoreEffect(state = state, listState = listState, onLoadMore = actions.onLoadMore)
}

/**
 * 触底加载。
 *
 * 键里带上 `items.size`：一页数据接上后如果仍在屏幕底部（比如平板一屏放得下），
 * 状态不变就不会重新触发，分页会卡住；数量一变就再判一次。
 */
@Composable
private fun LoadMoreEffect(state: FilterUiState, listState: LazyListState, onLoadMore: () -> Unit) {
    val nearEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }
    }
    // 拆成两步判断：三个条件写一行会触发 detekt 的 ComplexCondition
    val shouldLoad = nearEnd && state.hasMore
    LaunchedEffect(shouldLoad, state.items.size) {
        if (shouldLoad && !state.isLoading) onLoadMore()
    }
}

@Composable
private fun FilterFooter(isLoadingMore: Boolean, hasMore: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isLoadingMore -> CircularProgressIndicator(modifier = Modifier.size(IconSize.item))
            // 还有下一页但还没开始拉（用户没滑到底）：底部不占文字，也不提前说"到底了"
            hasMore -> Unit
            else -> Text(
                text = END_TEXT,
                style = MaterialTheme.typography.labelSmall,
                color = starveilExtraColors.textFaint,
            )
        }
    }
}

@Composable
private fun FilterError(message: String, onRetry: () -> Unit) {
    Centered {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = message, color = starveilExtraColors.textMuted)
            Text(
                text = "重试",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = Spacing.sm)
                    .clickable(onClick = onRetry),
            )
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

private const val COLUMNS = 3

/** 距底部还有几屏时开始拉下一页：留出两行缓冲，滑到底时数据已经在了。 */
private const val LOAD_MORE_THRESHOLD = 3

private const val FOOTER_KEY = "filter-footer"

private const val EMPTY_TEXT = "没有符合条件的影片"

private const val END_TEXT = "已经到底了"
