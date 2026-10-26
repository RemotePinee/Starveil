package com.starveil.feature.topic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.starveil.core.designsystem.component.StarveilLoadingScreen
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.Topic

/** 两列，对应旧列表卡片的整行封面 + 标题结构（`specialtopic_item2.xml`）。 */
private const val TOPIC_COLUMNS = 2

/** 专题封面是横版横幅，不是影片竖版海报。 */
private const val TOPIC_COVER_ASPECT = 16f / 9f

private const val TOPIC_BLURB_MAX_LINES = 2

/**
 * 专题列表入口。路由由 `:app` 决定，这里只把 topicId 上抛。
 */
@Composable
fun TopicRoute(
    viewModel: TopicViewModel,
    onTopicClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event -> snackbarHostState.showSnackbar(event.text) }
    }
    TopicScreen(
        state = state,
        onTopicClick = onTopicClick,
        onRetry = viewModel::retry,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

@Composable
fun TopicScreen(
    state: TopicListUiState,
    onTopicClick: (String) -> Unit,
    onRetry: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            TopicState(state = state, onTopicClick = onTopicClick, onRetry = onRetry)
            RefreshBar(
                refreshing = state is TopicListUiState.Content && state.isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun TopicState(
    state: TopicListUiState,
    onTopicClick: (String) -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        is TopicListUiState.Loading -> StarveilLoadingScreen()

        is TopicListUiState.Empty -> TopicEmpty(message = state.message, onRetry = onRetry)

        is TopicListUiState.Error -> TopicErrorBox(message = state.message, onRetry = onRetry)

        is TopicListUiState.Content -> TopicGrid(topics = state.topics, onTopicClick = onTopicClick)
    }
}

/** 刷新时只在顶部压一条细进度条，列表本身不重建。 */
@Composable
private fun RefreshBar(refreshing: Boolean, modifier: Modifier = Modifier) {
    if (refreshing) {
        LinearProgressIndicator(modifier = modifier.fillMaxWidth())
    }
}

/** 两列专题网格：列数、间距、内容边距与卡片结构集中在这里。 */
@Composable
internal fun TopicGrid(topics: List<Topic>, onTopicClick: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(TOPIC_COLUMNS),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.pageHorizontal),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        items(items = topics, key = { topic -> topic.id }) { topic ->
            TopicCard(topic = topic, onClick = { onTopicClick(topic.id) })
        }
    }
}

@Composable
private fun TopicCard(topic: Topic, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        TopicCardContent(topic = topic)
    }
}

@Composable
private fun TopicCardContent(topic: Topic) {
    AsyncImage(
        model = topic.poster,
        contentDescription = topic.name,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(TOPIC_COVER_ASPECT)
            .clip(AppShape.medium),
    )
    Text(
        text = topic.name,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = Spacing.sm),
    )
    Text(
        text = topic.blurb.orEmpty(),
        style = MaterialTheme.typography.bodySmall,
        color = starveilExtraColors.textMuted,
        maxLines = TOPIC_BLURB_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = Spacing.xxs),
    )
}

/**
 * 空态：整块居中，图标 + 标题 + 一句说明 + 重新加载。
 *
 * 早先是退化网格里的一行小字，落在左上角，看着像加载失败而不是「确实没有内容」。
 */
@Composable
private fun TopicEmpty(message: String, onRetry: () -> Unit) {
    CenteredBox {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(IconSize.lg * 2)
                    .clip(CircleShape)
                    .background(starveilExtraColors.surfaceElevated),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = PhosphorIcons.Regular.MagnifyingGlass,
                    contentDescription = null,
                    tint = starveilExtraColors.textMuted,
                    modifier = Modifier.size(IconSize.lg),
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = Spacing.md),
            )
            Text(
                text = "还没有发布专题，先回首页逛逛，过一会儿再来看看",
                style = MaterialTheme.typography.bodySmall,
                color = starveilExtraColors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.xs, start = Spacing.xxl, end = Spacing.xxl),
            )
            TextButton(onClick = onRetry, modifier = Modifier.padding(top = Spacing.lg)) {
                Text("重新加载")
            }
        }
    }
}

@Composable
private fun TopicErrorBox(message: String, onRetry: () -> Unit) {
    CenteredBox {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onRetry, modifier = Modifier.padding(top = Spacing.sm)) {
                Text("重试")
            }
        }
    }
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
