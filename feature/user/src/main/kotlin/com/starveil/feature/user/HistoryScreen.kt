package com.starveil.feature.user

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.starveil.core.designsystem.component.StarveilLoadingScreen
import com.starveil.core.designsystem.mirrorIconInRtl
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.ContinueWatching

/**
 * 观看记录：最近 10 条播放进度，点一条直接续播（跳播放页，位置读服务端断点）。
 *
 * 只有播放位置（`progressSec`），没有总时长，所以不画进度条——按百分比画会算出 100% 的假象。
 */
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onBack: () -> Unit,
    onVodClick: (ContinueWatching) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            SubPageTopBar(title = TITLE, onBack = onBack)
            when {
                state.isLoading -> StarveilLoadingScreen()
                state.errorMessage != null -> CenterBox {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = state.errorMessage.orEmpty(), color = starveilExtraColors.textMuted)
                        TextButton(onClick = viewModel::refresh) { Text(ACTION_RETRY) }
                    }
                }
                state.isEmpty -> CenterBox {
                    Text(text = EMPTY_HINT, color = starveilExtraColors.textFaint)
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(
                        start = Spacing.pageHorizontal,
                        end = Spacing.pageHorizontal,
                        top = Spacing.sm,
                        bottom = Spacing.xl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    items(items = state.items, key = { it.vodId }) { item ->
                        HistoryRow(item = item, onClick = { onVodClick(item) })
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(item: ContinueWatching, onClick: () -> Unit) {
    Surface(
        shape = AppShape.medium,
        color = starveilExtraColors.surfaceCard,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Box(
                modifier = Modifier
                    .width(POSTER_WIDTH)
                    .height(POSTER_HEIGHT)
                    .clip(AppShape.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                val pic = item.vodPic
                if (!pic.isNullOrBlank()) {
                    AsyncImage(
                        model = pic,
                        contentDescription = item.vodName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(
                    text = item.vodName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "第 ${item.episodeNumber} 集 · 看到 ${formatProgress(item.progressSec)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = starveilExtraColors.textFaint,
                )
            }
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/** 秒 → 分:秒，不四舍五入到分钟，避免"看到 1 分钟"其实才 20 秒。 */
private fun formatProgress(sec: Int): String {
    val safe = sec.coerceAtLeast(0)
    return "%d:%02d".format(safe / 60, safe % 60)
}

@Composable
private fun SubPageTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = PhosphorIcons.Regular.ArrowLeft,
                contentDescription = "返回",
                tint = starveilExtraColors.textMuted,
                modifier = Modifier.size(IconSize.md).mirrorIconInRtl(),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

private val POSTER_WIDTH = Spacing.xxl * 2 + Spacing.md
private val POSTER_HEIGHT = Spacing.xxl * 2 + Spacing.xl
private const val TITLE = "观看记录"
private const val ACTION_RETRY = "重试"
private const val EMPTY_HINT = "还没有观看记录"

