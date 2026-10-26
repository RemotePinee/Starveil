package com.starveil.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.ContinueWatching
import com.starveil.core.model.ContinueWatchingRules
import com.starveil.core.model.Vod

/**
 * 「继续观看」横滑卡：封面 + 片名 + "看到第 N 集 · xx%"。
 *
 * 点击复用 [HomeActions.onVodClick] 进详情：卡片只带 id / 名称 / 封面，
 * 详情页按 id 重新拉完整数据（见 [ContinueWatching.toVod]）。
 * 列表为空时整个区块不渲染。
 */
@Composable
fun ContinueWatchingSection(
    items: List<ContinueWatching>,
    onVodClick: (Vod) -> Unit,
) {
    val entries = items
    if (entries.isEmpty()) {
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionTitle(title = "继续观看")
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(items = entries, key = { it.vodId }) { item ->
                ContinueWatchingCard(
                    item = item,
                    onClick = { onVodClick(item.toVod()) },
                )
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(
    item: ContinueWatching,
    onClick: () -> Unit,
) {
    val percent = ContinueWatchingRules.percent(item.progressSec)
    Column(
        modifier = Modifier
            .width(CONTINUE_CARD_WIDTH)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CONTINUE_RATIO)
                .clip(AppShape.small),
        ) {
            AsyncImage(
                model = item.vodPic,
                contentDescription = item.vodName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(percent / 100f)
                    .height(PROGRESS_HEIGHT)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = item.vodName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "看到第 ${item.episodeNumber} 集 · $percent%",
            style = MaterialTheme.typography.labelSmall,
            color = starveilExtraColors.textMuted,
            maxLines = 1,
        )
    }
}

private val CONTINUE_CARD_WIDTH = Spacing.xxl * 3
private val PROGRESS_HEIGHT = Spacing.xxs
private const val CONTINUE_RATIO = 1.6f
