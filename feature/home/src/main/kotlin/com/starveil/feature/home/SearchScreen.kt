package com.starveil.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.starveil.core.designsystem.token.Motion
import kotlinx.coroutines.delay
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.Vod

/** 搜索页。关键词走后端 `wd` 参数，结果点击进详情。 */
@Composable
fun SearchRoute(
    viewModel: SearchViewModel,
    onVodClick: (Vod) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SearchScreen(
        state = state,
        actions = SearchActions(
            onQueryChange = viewModel::onQueryChange,
            onRetry = viewModel::retry,
            onVodClick = onVodClick,
            onBack = onBack,
            onHistoryClick = viewModel::onHistoryClick,
            onClearHistory = viewModel::onClearHistory,
            onRecommendationClick = onVodClick,
        ),
        modifier = modifier,
    )
}

@Composable
fun SearchScreen(
    state: SearchViewModel.State,
    actions: SearchActions,
    modifier: Modifier = Modifier,
) {
    // 进场统一走导航的横滑：整页不再自己从搜索胶囊缩出来，两套动画叠在一起会打架。
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            SearchHeader(query = state.query, onQueryChange = actions.onQueryChange, onBack = actions.onBack)
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error, color = starveilExtraColors.textMuted)
                        Text(
                            text = "重试",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(top = Spacing.sm)
                                .clickable(onClick = actions.onRetry),
                        )
                    }
                }

                state.searched && state.items.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("没有找到相关影片", color = starveilExtraColors.textMuted)
                }

                !state.searched -> SearchIdle(state = state, actions = actions)

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = state.items, key = { it.id }) { vod ->
                        SearchRow(vod = vod, onClick = { actions.onVodClick(vod) })
                    }
                    item { Spacer(Modifier.height(Spacing.xl)) }
                }
            }
        }
    }
}

@Composable
private fun SearchHeader(query: String, onQueryChange: (String) -> Unit, onBack: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // 等整页撑着的那段动画走完再弹键盘：同步弹会让缩放动画卡一下
    LaunchedEffect(Unit) {
        delay(Motion.LongMs.toLong())
        focusRequester.requestFocus()
        keyboard?.show()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        // 返回按钮换成圆形底之后不再自带 IconButton 的 48dp 内边距，
        // 两个控件会贴在一起；这里补回一条明确的间距。
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // 与右侧输入框同色同高的圆形底：两个控件在一条线上，形状语言要一致。
        Surface(
            onClick = onBack,
            shape = AppShape.full,
            color = starveilExtraColors.chipBackground,
            modifier = Modifier.size(Spacing.xxl + Spacing.sm),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = PhosphorIcons.Regular.ArrowLeft,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(IconSize.item).mirrorInRtl(),
                )
            }
        }
        Surface(
            modifier = Modifier
                .weight(1f)
                .height(Spacing.xxl + Spacing.sm),
            shape = AppShape.full,
            color = starveilExtraColors.chipBackground,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = PhosphorIcons.Regular.MagnifyingGlass,
                    contentDescription = null,
                    tint = starveilExtraColors.textFaint,
                    modifier = Modifier.size(IconSize.sm),
                )
                Spacer(Modifier.width(Spacing.sm))
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    ),
                    // 光标跟主题走：不设的话默认是黑色，深色主题下几乎看不见。
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                text = "搜索影片、演员",
                                // 与首页搜索胶囊同字号：占位文字不该比输入文字小一号。
                                style = MaterialTheme.typography.bodyMedium,
                                color = starveilExtraColors.textFaint,
                            )
                        }
                        inner()
                    },
                )
            }
        }
    }
}
/**
 * 未搜索时的着陆区：最近搜索 + 热播推荐。
 *
 * 之前这里只有一句「输入片名开始搜索」，等于把整屏浪费掉；进搜索页的人要么想重复
 * 上次的词，要么想看点什么——两件事都放得下。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchIdle(state: SearchViewModel.State, actions: SearchActions) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (state.history.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 用主题前景色：`onImage` 系是给视频上的白字用的，放在页面底色上
                    // 浅色主题会直接融进背景（标题和"清空"都撞过这个）。
                    Text(
                        text = "最近搜索",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "清空",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable(onClick = actions.onClearHistory),
                    )
                }
            }
            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    state.history.forEach { word ->
                        // 浅色模式不能用 imageGlass 系的白胶囊：它靠"白叠在画面上"成形，
                        // 浅底上直接融掉。用主题的容器色 + 对应前景色，两套主题都成立。
                        Surface(
                            shape = AppShape.full,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.clickable { actions.onHistoryClick(word) },
                        ) {
                            Text(
                                text = word,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                            )
                        }
                    }
                }
            }
        }
        if (state.recommendations.isNotEmpty()) {
            // 历史为空时列表直接顶上去：没有分区标题，也就没有那 40dp 留白。
            if (state.history.isNotEmpty()) {
                item {
                    Text(
                        text = "热播推荐",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(
                            start = Spacing.lg,
                            end = Spacing.lg,
                            top = Spacing.xl,
                            bottom = Spacing.sm,
                        ),
                    )
                }
            }
            items(items = state.recommendations, key = { it.id }) { vod ->
                SearchRow(vod = vod, onClick = { actions.onRecommendationClick(vod) })
            }
        }
        item { Spacer(Modifier.height(Spacing.xl)) }
    }
}

@Composable
private fun SearchRow(vod: Vod, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = vod.poster,
            contentDescription = vod.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(Spacing.xxl + Spacing.lg)
                .height(Spacing.xxl + Spacing.xxl + Spacing.lg)
                .clip(AppShape.small),
        )
        Column(
            modifier = Modifier
                .padding(start = Spacing.md)
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            Text(
                text = vod.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    vod.year?.takeIf { it.isNotBlank() },
                    vod.area?.takeIf { it.isNotBlank() },
                    vod.remarks?.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = starveilExtraColors.textMuted,
                maxLines = 1,
            )
        }
    }
}
