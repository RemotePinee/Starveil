package com.starveil.feature.topic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.starveil.core.designsystem.component.StarveilLoadingScreen
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.TopicDetail
import com.starveil.core.model.Vod

/** 内嵌影片沿用首页的三列海报网格。 */
private const val VOD_COLUMNS = 3

private const val TOPIC_COVER_ASPECT = 16f / 9f

private const val VOD_POSTER_ASPECT = 2f / 3f

private const val DETAIL_INTRO_MAX_LINES = 3

/** 旧详情页只删了 `<p>`，这里统一去掉所有标签。 */
private val HTML_TAG = Regex("<[^>]+>")
private val WHITESPACE = Regex("\\s+")

/**
 * 专题详情入口。影片点击只上抛 vodId，进播放页的路由由 `:app` 决定。
 */
@Composable
fun TopicDetailRoute(
    viewModel: TopicDetailViewModel,
    onVodClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event -> snackbarHostState.showSnackbar(event.text) }
    }
    TopicDetailScreen(
        state = state,
        onVodClick = onVodClick,
        onRetry = viewModel::retry,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

@Composable
fun TopicDetailScreen(
    state: TopicDetailUiState,
    onVodClick: (Int) -> Unit,
    onRetry: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            when (state) {
                is TopicDetailUiState.Loading -> StarveilLoadingScreen()

                is TopicDetailUiState.Error -> DetailErrorBox(state = state, onRetry = onRetry)

                is TopicDetailUiState.Empty -> VodGrid(
                    detail = state.detail,
                    emptyHint = state.message,
                    onVodClick = onVodClick,
                )

                is TopicDetailUiState.Content -> VodGrid(
                    detail = state.detail,
                    emptyHint = null,
                    onVodClick = onVodClick,
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * 三列影片网格：顶部头图占整行，影片按列排，空态时把影片区换成纯提示。
 */
@Composable
internal fun VodGrid(
    detail: TopicDetail,
    emptyHint: String?,
    onVodClick: (Int) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(VOD_COLUMNS),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.pageHorizontal),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            TopicHeader(detail = detail)
        }
        if (emptyHint != null) {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyHint(text = emptyHint) }
        } else {
            items(items = detail.vods, key = { vod -> vod.id }) { vod ->
                VodCard(vod = vod, onClick = { onVodClick(vod.id) })
            }
        }
    }
}

@Composable
private fun TopicHeader(detail: TopicDetail) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm)) {
        TopicHeaderContent(detail = detail)
    }
}

@Composable
private fun TopicHeaderContent(detail: TopicDetail) {
    AsyncImage(
        model = detail.poster,
        contentDescription = detail.name,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(TOPIC_COVER_ASPECT)
            .clip(AppShape.medium),
    )
    Text(
        text = detail.name,
        style = MaterialTheme.typography.headlineSmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = Spacing.md),
    )
    val intro = introOf(detail)
    if (intro.isNotEmpty()) {
        Text(
            text = intro,
            style = MaterialTheme.typography.bodySmall,
            color = starveilExtraColors.textMuted,
            maxLines = DETAIL_INTRO_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = starveilExtraColors.textMuted,
        modifier = Modifier.padding(vertical = Spacing.xl),
    )
}

@Composable
private fun VodCard(vod: Vod, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        VodCardContent(vod = vod)
    }
}

@Composable
private fun VodCardContent(vod: Vod) {
    AsyncImage(
        model = vod.poster,
        contentDescription = vod.name,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(VOD_POSTER_ASPECT)
            .clip(AppShape.medium),
    )
    Text(
        text = vod.name,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = Spacing.xs),
    )
    Text(
        text = vod.remarks.orEmpty(),
        style = MaterialTheme.typography.bodySmall,
        color = starveilExtraColors.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun DetailErrorBox(state: TopicDetailUiState.Error, onRetry: () -> Unit) {
    CenteredBox {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = state.message, style = MaterialTheme.typography.bodyMedium)
            if (state.retryable) {
                Button(onClick = onRetry, modifier = Modifier.padding(top = Spacing.sm)) {
                    Text("重试")
                }
            }
        }
    }
}

/** 简介优先用 blurb，缺省时退回 content 去标签后的纯文本。 */
private fun introOf(detail: TopicDetail): String {
    val blurb = detail.blurb?.trim().orEmpty()
    return blurb.ifEmpty {
        detail.content.orEmpty().replace(HTML_TAG, " ").replace(WHITESPACE, " ").trim()
    }
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
