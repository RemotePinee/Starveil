package com.starveil.feature.home

import androidx.compose.foundation.background
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
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.LiveChannel
import com.starveil.core.model.Topic

/**
 * 首页专题横滑：受 `home_modules` 的 `topic` 控制，列表为空时调用方整块不渲染。
 *
 * 只读列表，不做详情预取；点开去专题页由 `:app` 决定（feature 不持有导航）。
 */
@Composable
fun TopicRow(items: List<Topic>, onClick: (Topic) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(items = items, key = { it.id }) { topic ->
            DiscoverCard(
                name = topic.name,
                cover = topic.poster,
                onClick = { onClick(topic) },
            )
        }
    }
}

/** 首页直播横滑：受 `home_modules` 的 `live` 控制，列表为空时整块不出现。 */
@Composable
fun LiveRow(items: List<LiveChannel>, onClick: (LiveChannel) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(items = items, key = { it.id }) { live ->
            DiscoverCard(
                name = live.name,
                cover = live.cover,
                onClick = { onClick(live) },
            )
        }
    }
}

/** 专题与直播的卡片同构：封面 + 名称。 */
@Composable
private fun DiscoverCard(
    name: String,
    cover: String?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(DISCOVER_CARD_WIDTH)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = cover,
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(DISCOVER_CARD_RATIO)
                .clip(AppShape.medium)
                .background(starveilExtraColors.surfaceCard),
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 横滑一屏约两张半，露半张提示可滑。 */
private val DISCOVER_CARD_WIDTH = Spacing.xxl * 3

/** 专题/直播封面偏横图，与竖版海报区分开。 */
private const val DISCOVER_CARD_RATIO = 1.4f
