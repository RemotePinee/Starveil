package com.starveil.feature.rank

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.Check
import com.starveil.core.designsystem.component.StarveilLoadingScreen
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing

/**
 * 榜单页入口。是 RankFragment / RankFragmentB / RankFragmentC 三套布局，
 * 新实现只有一套，皮肤差异留在令牌层（AGENTS.md 第 3 节）。
 */
@Composable
fun RankRoute(
    viewModel: RankViewModel,
    onVodClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RankScreen(
        state = state,
        actions = RankActions(
            onCategorySelect = viewModel::selectCategory,
            onOrderSelect = viewModel::selectOrder,
            onLoadMore = viewModel::loadMore,
            onRetry = viewModel::retry,
            onVodClick = onVodClick,
        ),
        modifier = modifier,
    )
}

/**
 * 版式：固定顶栏（标题 + 分类下拉 + 日/周/月分段），下面才是可替换的内容区。
 *
 * 顶栏**不随加载状态变化**：Loading / Empty / Error 只替换内容区，用户永远能切维度
 * 或切分类，不会像整页替换那样"卡死在错误页、回不去"。
 */
@Composable
fun RankScreen(
    state: RankUiState,
    actions: RankActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            RankHeader(state = state, actions = actions)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                RankBody(state = state, actions = actions)
            }
        }
    }
}

@Composable
private fun RankHeader(
    state: RankUiState,
    actions: RankActions,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = Spacing.md)) {
        Text(
            text = RANK_TITLE,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = Spacing.pageHorizontal),
        )
        // 说明与分类入口同处一行、两端对齐：小字靠左、入口靠右，右边缘和说明行严格对齐。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 固定行高：分类下拉在 catalog 到达后才出现，之前只有一行小字，
                // 行高不锁死的话首帧会比稳定态矮一截，Tab 和列表会往下窜一下
                .height(Spacing.xxl + Spacing.sm)
                .padding(start = Spacing.pageHorizontal, end = Spacing.pageHorizontal, top = Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = periodHint(state.order),
                style = MaterialTheme.typography.bodySmall,
                color = starveilExtraColors.textFaint,
                modifier = Modifier.weight(1f),
            )
            when (val catalog = state.catalog) {
                is RankCatalogState.Content -> RankCategoryMenu(
                    categories = catalog.categories,
                    selectedTypeId = state.selectedTypeId,
                    onSelect = actions.onCategorySelect,
                )

                else -> Unit
            }
        }
        RankPeriodTabs(
            selected = state.order,
            onSelect = actions.onOrderSelect,
            modifier = Modifier.padding(
                start = Spacing.pageHorizontal,
                end = Spacing.pageHorizontal,
                top = Spacing.md,
            ),
        )
    }
}

/** 内容区只切分支，不改动顶栏；分类与列表两层状态各自独立降级。 */
@Composable
private fun RankBody(state: RankUiState, actions: RankActions) {
    // 目录和榜单是两个接口：catalog 到 Content 之后 board 还会再 Loading 一次。
    // 判在一处，首屏加载只用一个调用点，切换时不会重建加载动画。
    if (state.isFirstLoad()) {
        StarveilLoadingScreen()
        return
    }
    when (val catalog = state.catalog) {
        RankCatalogState.Empty -> RankStateMessage(
            title = "暂无榜单分类",
            message = "榜单目录还没准备好，稍后重试即可",
            actionLabel = "重试",
            onAction = actions.onRetry,
        )

        is RankCatalogState.Error -> RankStateMessage(
            title = "榜单加载失败",
            message = catalog.message,
            actionLabel = "重试",
            onAction = actions.onRetry,
        )

        is RankCatalogState.Content -> RankBoardBody(board = state.board, actions = actions)
        // 上面的提前返回已经吃掉 Loading，这里只是让 when 穷尽。
        RankCatalogState.Loading -> Unit
    }
}

/** 目录未到、或目录已到但首屏榜单还在路上，都算"该显示加载态"。 */
private fun RankUiState.isFirstLoad(): Boolean {
    if (catalog is RankCatalogState.Loading) return true
    val board = this.board
    return catalog is RankCatalogState.Content &&
        (board == RankBoardState.Idle || board == RankBoardState.Loading)
}

@Composable
private fun RankBoardBody(
    board: RankBoardState,
    actions: RankActions,
) {
    when (board) {
        RankBoardState.Idle, RankBoardState.Loading -> StarveilLoadingScreen()
        RankBoardState.Empty -> RankStateMessage(
            title = "该分类暂无榜单",
            message = "换个分类或时间维度看看",
            actionLabel = "重试",
            onAction = actions.onRetry,
        )

        is RankBoardState.Error -> RankStateMessage(
            title = "榜单加载失败",
            message = board.message,
            actionLabel = "重试",
            onAction = actions.onRetry,
        )

        // 换维度直接替换列表，不叠进度条：那条细线闪得比内容还抢眼，
        // 而列表本来就不清空，用户点完看到的就是新数据
        is RankBoardState.Content -> RankBoardList(board = board, actions = actions)
    }
}

/**
 * 分类下拉：触发器是"当前分类 + 箭头"的一小段文字，展开是贴着它的下拉菜单。
 *
 * 触发器是右侧一颗圆形按钮：不带文字、不带箭头，图标固定为分类。
 * 分类名放到菜单里用选中态表达，按钮不用跟着分类名变宽，和时间分段并排时也不会互相挤。
 *
 * 菜单定宽并向左偏移，右边缘和按钮对齐。Material 的 DropdownMenu 默认从锚点左下角
 * 往右展开，宽菜单会一路捅到屏幕外、和按钮完全不成一条线。
 * 选项 40dp 高、圆角、选中项浅主色底 + 勾；默认 48dp 满宽条目在暗色下一整列都是直角黑块。
 *
 * 展开是纯 UI 局部状态，不进 RankUiState：进状态机等于多一次事件往返，没有收益。
 */
@Composable
private fun RankCategoryMenu(
    categories: List<RankCategory>,
    selectedTypeId: Int?,
    onSelect: (Int) -> Unit,
) {
    // 后端偶尔会塞回一条空名字的分类：选项里没有文字，只剩一块底色。过滤掉，数据本身不动。
    val visible = categories.filter { it.name.isNotBlank() }
    if (visible.isEmpty()) return
    val current = visible.firstOrNull { it.typeId == selectedTypeId } ?: visible.first()
    var expanded by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val offsetX = with(density) { (anchorWidth - CATEGORY_MENU_WIDTH.toPx()).toDp() }
    Box {
        Row(
            modifier = Modifier
                .onGloballyPositioned { anchorWidth = it.size.width }
                .onGloballyPositioned { anchorWidth = it.size.width }
                .clip(AppShape.full)
                .clickable { expanded = true }
                .padding(start = Spacing.sm, top = Spacing.xxs, bottom = Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = current.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = starveilExtraColors.textMuted,
            )
            Icon(
                imageVector = PhosphorIcons.Regular.CaretDown,
                contentDescription = "切换分类",
                tint = starveilExtraColors.textMuted,
                modifier = Modifier
                    .padding(start = Spacing.xxs)
                    .size(IconSize.sm),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(CATEGORY_MENU_WIDTH),
            offset = DpOffset(x = offsetX, y = Spacing.xxs),
            shape = AppShape.medium,
            containerColor = starveilExtraColors.surfaceCard,
            tonalElevation = Spacing.none,
        ) {
            Column(modifier = Modifier.padding(Spacing.xs)) {
                visible.forEach { category ->
                    RankCategoryOption(
                        category = category,
                        selected = category.typeId == current.typeId,
                        onClick = {
                            expanded = false
                            onSelect(category.typeId)
                        },
                    )
                }
            }
        }
    }
}

/** 下拉的一项：选中项浅主色底 + 主色文字 + 勾，其余是次级灰，一眼看出当前在哪一类。 */
@Composable
private fun RankCategoryOption(
    category: RankCategory,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = SELECTED_TINT_ALPHA)
    } else {
        Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(CATEGORY_OPTION_HEIGHT)
            .clip(AppShape.small)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = category.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                starveilExtraColors.textMuted
            },
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = PhosphorIcons.Regular.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(IconSize.sm),
            )
        }
    }
}

/** 下拉菜单定宽：分类名两到三字，这个宽度够放下并留出勾位。 */
private val CATEGORY_MENU_WIDTH = Spacing.xxl * 3 + Spacing.md

/** 选项行高：比系统 48dp 的菜单行更紧，一屏能放完全部分类。 */
private val CATEGORY_OPTION_HEIGHT = Spacing.xxl + Spacing.sm

/** 选中项底色透明度：只做一层很浅的染色，靠文字和勾表达选中，不抢主色。 */
private const val SELECTED_TINT_ALPHA = 0.14f

private const val RANK_TITLE = "排行榜"

