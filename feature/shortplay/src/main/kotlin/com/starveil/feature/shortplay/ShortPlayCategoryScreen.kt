package com.starveil.feature.shortplay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import com.adamglin.phosphoricons.regular.ChatCircle
import com.adamglin.phosphoricons.regular.ClockCounterClockwise
import com.adamglin.phosphoricons.regular.Heart
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.starveil.core.designsystem.component.StarveilLoadingScreen
import com.starveil.core.designsystem.mirrorIconInRtl
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.ShortPlay
import com.starveil.core.model.ShortPlayType

/**
 * 短剧分类页入口。
 *
 * 页面不持有 NavController，跳转全部走 [ShortPlayCategoryActions] 回调（AGENTS.md 第 8 节）。
 */
@Composable
fun ShortPlayCategoryRoute(
    viewModel: ShortPlayCategoryViewModel,
    onBack: () -> Unit,
    onPlay: (ShortPlay) -> Unit,
    navigation: ShortPlayNavigation = ShortPlayNavigation(),
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ShortPlayCategoryScreen(
        state = state,
        actions = ShortPlayCategoryActions(
            onSelectParent = viewModel::selectParent,
            onSelectChild = viewModel::selectChild,
            onLoadMore = viewModel::loadMore,
            onRetry = viewModel::retry,
            onRefresh = viewModel::refresh,
            onPlay = onPlay,
            onBack = onBack,
            navigation = navigation,
        ),
        modifier = modifier,
    )
}

/**
 * 两级分类条固定在顶部，只有卡片网格滚动——用户点完一项要立刻看到自己在哪儿，
 * 让它滚走等于每次都得先滑回顶部。
 *
 * 底色用与剧场页同一块深底：分类条是剧场页那一条（白字 + DstOut 渐隐），
 * 换到浅色底上会整条看不见，所以整页保持深色，不另造一种选中态。
 */
@Composable
fun ShortPlayCategoryScreen(
    state: ShortPlayCategoryUiState,
    actions: ShortPlayCategoryActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(starveilExtraColors.scrimStrong),
    ) {
        Column(modifier = Modifier.statusBarsPadding()) {
            ShortPlayCategoryTopBar(onBack = actions.onBack, onSearch = actions.navigation.onSearch)
            ShortPlayCategoryTree(
                state = state,
                onSelectParent = actions.onSelectParent,
                onSelectChild = actions.onSelectChild,
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            when {
                state.isLoading && state.items.isEmpty() -> StarveilLoadingScreen()
                state.error != null && state.items.isEmpty() ->
                    ShortPlayCategoryNotice(message = state.error.orEmpty(), actionLabel = "重试") {
                        actions.onRetry()
                    }

                state.isEmpty -> ShortPlayCategoryNotice(message = EMPTY_TEXT, actionLabel = "刷新") {
                    actions.onRefresh()
                }

                else -> ShortPlayCategoryGrid(state = state, actions = actions)
            }
        }
        ShortPlayCategoryLinks(actions = actions)
    }
}

/** 顶栏：返回 + 标题 + 搜索。搜索是入口不是分类维度，所以只上报动作。 */
@Composable
private fun ShortPlayCategoryTopBar(onBack: () -> Unit, onSearch: () -> Unit) {
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
                tint = starveilExtraColors.onImage,
                modifier = Modifier.size(IconSize.md).mirrorIconInRtl(),
            )
        }
        Text(
            text = "短剧分类",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = starveilExtraColors.onImage,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSearch) {
            Icon(
                imageVector = PhosphorIcons.Regular.MagnifyingGlass,
                contentDescription = "搜索",
                tint = starveilExtraColors.onImage,
                modifier = Modifier.size(IconSize.md),
            )
        }
    }
}

/**
 * 两级分类条。
 *
 * - 一级只有 ≥2 个父分类时才出现：只有一个时直接展示它的二级，不留一条单选项 tab；
 * - 二级首项是客户端虚拟的「全部」（id 0），选了它按父分类查，服务端会带上全部子分类；
 * - 父分类没有子分类时二级行整行不出现（老配置只填了叶子分类）。
 */
@Composable
private fun ShortPlayCategoryTree(
    state: ShortPlayCategoryUiState,
    onSelectParent: (Int) -> Unit,
    onSelectChild: (Int) -> Unit,
) {
    if (state.isEmptyTree) {
        Text(
            text = "后台暂无分类",
            style = MaterialTheme.typography.bodySmall,
            color = starveilExtraColors.textMuted,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        )
        return
    }
    if (state.showParents) {
        ShortPlayCategoryRow(
            categories = state.parents,
            selectedTypeId = state.selectedParentId,
            enabled = true,
            onSelect = onSelectParent,
            leadingPadding = Spacing.lg,
        )
    }
    if (state.showChildren) {
        ShortPlayCategoryRow(
            categories = withAllOption(state.children),
            selectedTypeId = state.selectedChildId,
            enabled = true,
            onSelect = onSelectChild,
            leadingPadding = Spacing.lg,
        )
    }
}

/** 二级行 = 虚拟「全部」+ 当前父分类的子分类。 */
private fun withAllOption(children: List<ShortPlayType>): List<ShortPlayType> =
    listOf(ShortPlayType(id = ALL_CATEGORY_ID, name = ALL_CATEGORY_LABEL)) + children

/** 三列卡片网格。按列数把列表切成行，避免在 LazyColumn 里嵌 LazyVerticalGrid。 */
@Composable
private fun ShortPlayCategoryGrid(state: ShortPlayCategoryUiState, actions: ShortPlayCategoryActions) {
    val listState = rememberLazyListState()
    // 换分类后回到顶部：结果集整批换了，停在原位会看到"剩下半屏都是空的"
    LaunchedEffect(state.selectedParentId, state.selectedChildId) { listState.scrollToItem(0) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = Spacing.sm, bottom = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        items(items = state.items.chunked(COLUMNS), key = { row -> row.first().id }) { row ->
            ShortPlayGridRow(row = row, onPlay = actions.onPlay)
        }
        item(key = FOOTER_KEY) {
            ShortPlayGridFooter(isLoadingMore = state.isLoadingMore, hasMore = state.hasMore)
        }
    }
    LoadMoreEffect(state = state, listState = listState, onLoadMore = actions.onLoadMore)
}

@Composable
private fun ShortPlayGridRow(row: List<ShortPlay>, onPlay: (ShortPlay) -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        row.forEach { play ->
            ShortPlayPosterCard(
                play = play,
                onClick = { onPlay(play) },
                modifier = Modifier.weight(1f),
            )
        }
        // 最后一行不足列数时补空位，保持卡片宽度一致
        repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

/**
 * 触底加载。
 *
 * 键里带上 `items.size`：一页数据接上后如果仍在屏幕底部（比如平板一屏放得下），
 * 状态不变就不会重新触发，分页会卡住；数量一变就再判一次。
 */
@Composable
private fun LoadMoreEffect(
    state: ShortPlayCategoryUiState,
    listState: LazyListState,
    onLoadMore: () -> Unit,
) {
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
private fun ShortPlayGridFooter(isLoadingMore: Boolean, hasMore: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isLoadingMore -> CircularProgressIndicator(modifier = Modifier.size(IconSize.item))
            // 还有下一页但还没开始拉：底部不占文字，也不提前说"到底了"
            hasMore -> Unit
            else -> Text(
                text = END_TEXT,
                style = MaterialTheme.typography.labelSmall,
                color = starveilExtraColors.textFaint,
            )
        }
    }
}

/** 空结果 / 失败：一句提示 + 一个动作，不跳整页错误态。 */
@Composable
private fun ShortPlayCategoryNotice(message: String, actionLabel: String, onAction: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = message, color = starveilExtraColors.textMuted)
            Text(
                text = actionLabel,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = Spacing.sm)
                    .clickable(onClick = onAction),
            )
        }
    }
}

/**
 * 底部快捷入口。
 *
 * 原先在剧场页侧边抽屉里；抽屉撤掉后挪到这里，四个入口一个不少：
 * 观看历史、我的收藏、反馈，以及刷新当前分类的「换一批」。
 */
@Composable
private fun ShortPlayCategoryLinks(actions: ShortPlayCategoryActions) {
    HorizontalDivider(color = starveilExtraColors.divider)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        ShortPlayCategoryLink(
            label = "观看历史",
            icon = PhosphorIcons.Regular.ClockCounterClockwise,
            onClick = actions.navigation.onHistory,
            modifier = Modifier.weight(1f),
        )
        ShortPlayCategoryLink(
            label = "我的收藏",
            icon = PhosphorIcons.Regular.Heart,
            onClick = actions.navigation.onFavorites,
            modifier = Modifier.weight(1f),
        )
        ShortPlayCategoryLink(
            label = "反馈",
            icon = PhosphorIcons.Regular.ChatCircle,
            onClick = actions.navigation.onFeedback,
            modifier = Modifier.weight(1f),
        )
        ShortPlayCategoryLink(
            label = "换一批",
            icon = PhosphorIcons.Regular.ArrowsClockwise,
            onClick = actions.onRefresh,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 一行快捷入口：图标在上、文案在下，四个等宽排开。 */
@Composable
private fun ShortPlayCategoryLink(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            // 行内已有文字，图标不再重复朗读。
            contentDescription = null,
            tint = starveilExtraColors.onImageSubtle,
            modifier = Modifier.size(IconSize.item),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = starveilExtraColors.onImageSubtle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.xxs),
        )
    }
}

private const val COLUMNS = 3

/** 距底部还有几行时开始拉下一页：留出缓冲，滑到底时数据已经在了。 */
private const val LOAD_MORE_THRESHOLD = 3

private const val FOOTER_KEY = "shortplay-category-footer"

private const val EMPTY_TEXT = "这个分类还没有短剧"

private const val END_TEXT = "已经到底了"
