package com.starveil.feature.user

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.starveil.core.designsystem.component.StarveilLoadingScreen
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.FavoriteItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 我的收藏。只渲染状态 + 上抛事件；分页、删除、错误文案都在 [FavoriteViewModel]。
 *
 * 是 `CollectionActivity`：`SmartRefreshLayout` 上拉加载（`:151-153`）、
 * 编辑模式多选删除（`:216-240`）。新实现保留分页与单条取消收藏，不做多选编辑——
 * 旧页面删除后整页重拉（`:203-206`），批量删时用户会连着丢两次滚动位置。
 */
@Composable
fun FavoriteScreen(
    viewModel: FavoriteViewModel,
    onBack: () -> Unit,
    onVodClick: (FavoriteItem) -> Unit,
    onLoginClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.consumeMessage()
    }
    val actions = FavoriteActions(
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::retry,
        onRemove = viewModel::remove,
        onVodClick = onVodClick,
        onLoginClick = onLoginClick,
        onBack = onBack,
    )
    FavoriteContent(state = state, actions = actions, modifier = modifier)
}

/** 收藏页回调集合，避免 [FavoriteContent] 参数越堆越多（detekt 上限 5 个）。 */
private data class FavoriteActions(
    val onRefresh: () -> Unit,
    val onLoadMore: () -> Unit,
    val onRetry: () -> Unit,
    val onRemove: (FavoriteItem) -> Unit,
    val onVodClick: (FavoriteItem) -> Unit,
    val onLoginClick: () -> Unit,
    val onBack: () -> Unit,
)

@Composable
private fun FavoriteContent(
    state: FavoriteUiState,
    actions: FavoriteActions,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            when {
                state.needsLogin -> PagePlaceholder(
                    title = "登录后查看收藏",
                    detail = "收藏需要登录后才能同步到账号",
                    actionLabel = "去登录",
                    onAction = actions.onLoginClick,
                )

                state.isInitialLoading -> StarveilLoadingScreen()

                state.isError -> PagePlaceholder(
                    title = "收藏加载失败",
                    detail = state.errorMessage.orEmpty(),
                    actionLabel = "重试",
                    onAction = actions.onRetry,
                )

                state.isEmpty -> PagePlaceholder(
                    title = "还没有收藏",
                    detail = "在详情页点收藏，影片就会出现在这里",
                    actionLabel = "返回",
                    onAction = actions.onBack,
                )

                else -> FavoriteList(state = state, actions = actions)
            }
        }
    }
}

/** 列表本体：下拉刷新 + 触底加载。页脚进入组合区即视为"到底了"，等价的上拉监听。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoriteList(state: FavoriteUiState, actions: FavoriteActions) {
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = actions.onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            contentPadding = PaddingValues(
                horizontal = Spacing.pageHorizontal,
                vertical = Spacing.md,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            items(items = state.items, key = { it.ulogId }) { item ->
                FavoriteRow(
                    item = item,
                    isRemoving = item.vodId in state.removingIds,
                    onClick = { actions.onVodClick(item) },
                    onRemove = { actions.onRemove(item) },
                )
            }
            item(key = FOOTER_KEY) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.md),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        state.isLoadingMore -> CircularProgressIndicator(
                            modifier = Modifier.size(Spacing.xl),
                        )

                        state.hasMore -> {
                            LaunchedEffect(state.items.size) { actions.onLoadMore() }
                            Text(
                                text = "上拉加载更多",
                                style = MaterialTheme.typography.bodySmall,
                                color = starveilExtraColors.textMuted,
                            )
                        }

                        else -> Text(
                            text = "没有更多了",
                            style = MaterialTheme.typography.bodySmall,
                            color = starveilExtraColors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

/** 海报 + 标题 + 分类 + 收藏时间 + 取消按钮。海报按比例占行宽，不写裸 dp。 */
@Composable
private fun FavoriteRow(
    item: FavoriteItem,
    isRemoving: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val posterPlaceholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.poster?.takeIf { it.isNotBlank() },
            contentDescription = item.title,
            placeholder = posterPlaceholder,
            error = posterPlaceholder,
            fallback = posterPlaceholder,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth(POSTER_WIDTH_FRACTION)
                .aspectRatio(POSTER_ASPECT_RATIO)
                .clip(AppShape.small),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            Text(
                text = item.title.ifBlank { "影片已下架" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.categoryName?.takeIf { it.isNotBlank() } ?: "未分类",
                style = MaterialTheme.typography.bodySmall,
                color = starveilExtraColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "收藏于 ${formatFavoriteTime(item.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = starveilExtraColors.textMuted,
                maxLines = 1,
            )
        }
        TextButton(onClick = onRemove, enabled = !isRemoving) {
            Text(
                text = if (isRemoving) "取消中" else "取消收藏",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * 空态 / 错误态 / 未登录共用一个居中占位。
 *
 * 两个页面（收藏 / 资料）都用它，但 `:core:designsystem` 不归本模块维护，
 * 所以先 `internal` 放在这里，等 designsystem 收编后再搬。
 */
@Composable
internal fun PagePlaceholder(
    title: String,
    detail: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = starveilExtraColors.textMuted,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        Button(
            onClick = onAction,
            shape = AppShape.small,
            modifier = Modifier.padding(top = Spacing.lg),
        ) {
            Text(text = actionLabel, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * 收藏时间是秒级 `ulog_time`（见 `FavoriteMapper`），乘 1000 才是 `Date` 要的毫秒；
 * 0 表示后端没给时间，直接显示"未知时间"而不是 1970。
 *
 * 每次调用新建 formatter：`SimpleDateFormat` 非线程安全，不能提成全局单例。
 */
private fun formatFavoriteTime(seconds: Long): String {
    if (seconds <= 0L) return "未知时间"
    val formatter = SimpleDateFormat(TIME_PATTERN, Locale.getDefault())
    return formatter.format(Date(seconds * MILLIS_PER_SECOND))
}

private const val TIME_PATTERN = "yyyy-MM-dd HH:mm"
private const val MILLIS_PER_SECOND = 1000L
private const val FOOTER_KEY = "favorite_footer"

/** 海报宽度占行宽比例，与 `HomeScreen` 的横排列表保持一致。 */
private const val POSTER_WIDTH_FRACTION = 0.28f
private const val POSTER_ASPECT_RATIO = 2f / 3f

