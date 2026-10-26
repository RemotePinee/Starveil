package com.starveil.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.HomeBlock
import com.starveil.core.model.Vod

/**
 * 专区：横滑时只露一张半提示可滑；网格复用海报网格。
 */
@Composable
fun HomeBlockSection(block: HomeBlock, onVodClick: (Vod) -> Unit) {
    val vods = block.vods
    if (vods.isEmpty()) {
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionTitle(
            title = block.title,
            action = block.subtitle.takeIf { it.isNotBlank() },
        )
        when (block.layout) {
            "grid2", "grid3" -> PosterRows(
                items = vods,
                columns = if (block.layout == "grid2") 2 else 3,
                onVodClick = onVodClick,
            )

            else -> BlockStripe(vods = vods, onVodClick = onVodClick)
        }
    }
}

/** 专区横滑条：一屏露两张半；宽度与海报比例跟真实卡片共用一套常量。 */
@Composable
private fun BlockStripe(vods: List<Vod>, onVodClick: (Vod) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(items = vods, key = { it.id }) { vod ->
            Column(
                modifier = Modifier
                    .width(BLOCK_CARD_WIDTH)
                    .clickable { onVodClick(vod) },
            ) {
                AsyncImage(
                    model = vod.poster,
                    contentDescription = vod.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(BLOCK_RATIO)
                        .clip(AppShape.small),
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = vod.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val BLOCK_CARD_WIDTH = Spacing.xxl * 2 + Spacing.lg

/** 专区卡片比例：跟真实卡片同一套值。 */
private const val BLOCK_RATIO = 0.66f
