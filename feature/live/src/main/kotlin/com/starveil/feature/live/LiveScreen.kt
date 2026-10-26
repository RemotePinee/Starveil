package com.starveil.feature.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starveil.core.data.LiveGroup
import com.starveil.core.designsystem.component.StarveilLoadingScreen
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing

/** 网格列数：两列一屏能放下 6~8 个台，单列太浪费纵向空间。 */
private const val GRID_COLUMNS = 2

/**
 * 直播页入口：只接列表状态。
 *
 * 播放不再常驻在页头（原来是一个 16:9 小窗、不能全屏也不能换台），
 * 改成点频道直接进全屏播放器，所以这里不再需要播放 ViewModel。
 */
@Composable
fun LiveRoute(
    listViewModel: LiveViewModel,
    modifier: Modifier = Modifier,
) {
    val listState by listViewModel.state.collectAsStateWithLifecycle()
    LiveScreen(
        listState = listState,
        actions = LiveActions(
            onRefresh = listViewModel::refresh,
            onLoadMore = listViewModel::loadMore,
            onSelectGroup = listViewModel::selectGroup,
        ),
        modifier = modifier,
    )
}

/**
 * 直播页：标题 + 分类条 + 两列网格。
 *
 * 分类来自后端（M3U 的 `group-title`），第一项是「全部」；
 * 标题里的"共 N 路"取全量 total，不是已加载条数——滑一页数字就变会让人以为在丢数据。
 */
@Composable
fun LiveScreen(
    listState: LiveUiState,
    actions: LiveActions,
    modifier: Modifier = Modifier,
) {
    // 全屏播放器要定位到被点的那一条，所以记的是下标而不是频道对象
    var fullscreenIndex by remember { mutableStateOf<Int?>(null) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 外壳全局沉浸后不再替页面留状态栏位置：背景铺满，内容从状态栏下方开始
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            LiveHeader(
                subtitle = headerSubtitle(listState),
                isRefreshing = (listState as? LiveUiState.Content)?.isRefreshing == true,
                onRefresh = actions.onRefresh,
            )
            val content = listState as? LiveUiState.Content
            if (content != null && content.groups.size > 1) {
                LiveGroupBar(
                    groups = content.groups,
                    active = content.activeGroup,
                    onSelect = actions.onSelectGroup,
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                LiveContent(
                    listState = listState,
                    actions = actions,
                    onOpen = { index -> fullscreenIndex = index },
                )
            }
        }
    }
    // 分成两层判空：detekt 的 ComplexCondition 阈值就是 3
    val current = listState as? LiveUiState.Content
    val target = fullscreenIndex
    if (current != null && target != null) {
        if (current.items.isNotEmpty()) {
            LiveFullscreenPlayer(
                model = LivePickerModel(
                    entries = current.items.mapIndexed { i, channel -> PickerEntry(i, channel) },
                    groups = current.groups,
                    current = target,
                    activeGroup = current.activeGroup,
                ),
                initialIndex = target,
                onDismiss = { fullscreenIndex = null },
                onSelectGroup = actions.onSelectGroup,
            )
        }
    }
}

/** 分类条：横向滚动的小胶囊，选中项用主色填充。 */
@Composable
private fun LiveGroupBar(
    groups: List<LiveGroup>,
    active: String,
    onSelect: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Spacing.pageHorizontal, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        items(items = groups, key = { it.name }) { group ->
            val selected = group.name == active
            Box(
                modifier = Modifier
                    .clip(AppShape.full)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            starveilExtraColors.surfaceCard
                        },
                    )
                    .clickable { onSelect(group.name) }
                    .padding(horizontal = Spacing.md, vertical = Spacing.xxs),
            ) {
                Text(
                    text = groupLabel(group),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        starveilExtraColors.textMuted
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/** 「全部 149」/「央视 20」：分类名留空表示全部。 */
private fun groupLabel(group: LiveGroup): String =
    "${group.name.ifBlank { "全部" }} ${group.count}"

@Composable
private fun LiveContent(
    listState: LiveUiState,
    actions: LiveActions,
    onOpen: (Int) -> Unit,
) {
    when (listState) {
        is LiveUiState.Loading -> StarveilLoadingScreen()

        is LiveUiState.Empty -> LiveEmptyState(
            message = listState.message,
            onRefresh = actions.onRefresh,
        )

        is LiveUiState.Error -> LiveErrorState(
            message = listState.message,
            onRefresh = actions.onRefresh,
        )

        is LiveUiState.Content -> LiveChannelGrid(
            state = listState,
            onLoadMore = actions.onLoadMore,
            onOpen = onOpen,
        )
    }
}

/**
 * 频道网格 + 触底翻页。
 *
 * 翻页用底部整行那一项触发：`LaunchedEffect(items.size)` 只在条目数变化时重跑，
 * 不会因为重组反复请求；重复请求由 ViewModel 的 `isLoadingMore` / `hasMore` 挡掉。
 */
@Composable
private fun LiveChannelGrid(
    state: LiveUiState.Content,
    onLoadMore: () -> Unit,
    onOpen: (Int) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.pageHorizontal,
            end = Spacing.pageHorizontal,
            top = Spacing.xs,
            bottom = Spacing.xxl,
        ),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        itemsIndexed(items = state.items, key = { _, channel -> channel.id }) { index, channel ->
            LiveChannelCard(
                channel = channel,
                // 列表里不挂播放状态：正在播的那台只靠全屏播放器显示
                playback = null,
                onClick = { onOpen(index) },
            )
        }
        item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
            LiveGridFooter(state = state, onLoadMore = onLoadMore)
        }
    }
}

/** 网格底部：翻页触发器 / 加载中 / 到底了。 */
@Composable
private fun LiveGridFooter(state: LiveUiState.Content, onLoadMore: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        when {
            state.isLoadingMore -> CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = Spacing.xxs,
                modifier = Modifier.size(IconSize.item),
            )

            state.hasMore -> {
                // 到底部就取下一页；条目数作为 key，翻一页触发一次
                LaunchedEffect(state.items.size) { onLoadMore() }
            }

            state.items.isNotEmpty() -> Text(
                text = "已经到底了",
                style = MaterialTheme.typography.bodySmall,
                color = starveilExtraColors.textFaint,
            )
        }
    }
}

/** 顶部副标题只陈述当前状态，不参与业务分支。用全量 total，不用已加载条数。 */
private fun headerSubtitle(state: LiveUiState): String = when (state) {
    is LiveUiState.Loading -> "正在加载频道"
    is LiveUiState.Content -> "共 ${state.total} 路频道 · 实时更新"
    is LiveUiState.Empty -> "暂无频道"
    is LiveUiState.Error -> "加载失败"
}
