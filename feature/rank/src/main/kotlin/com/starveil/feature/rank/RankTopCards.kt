package com.starveil.feature.rank

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.Vod

/**
 * 前三名：三张 2:3 竖版大卡横排，是页面的视觉重心。
 *
 * 不足三部时补 [Spacer] 顶位，而不是让剩下的卡按权重拉伸——拉伸会让第 1 名
 * 突然占半屏，破坏"名次越大卡越小"的层级。
 */
@Composable
internal fun RankTopRow(
    items: List<Vod>,
    onVodClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val top = items.take(TOP_RANK_COUNT)
    if (top.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        top.forEachIndexed { index, vod ->
            RankTopCard(
                rank = index + 1,
                vod = vod,
                onClick = { onVodClick(vod.id) },
                modifier = Modifier.weight(1f),
            )
        }
        repeat(TOP_RANK_COUNT - top.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun RankTopCard(
    rank: Int,
    vod: Vod,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(AppShape.medium)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(RANK_POSTER_RATIO)
                .clip(AppShape.medium)
                .background(starveilExtraColors.surfaceCard),
        ) {
            RankPoster(
                poster = vod.poster,
                name = vod.name,
                modifier = Modifier.matchParentSize(),
            )
            RankNumberScrim(rank = rank)
        }
        TopCardTexts(vod = vod)
    }
}

@Composable
private fun TopCardTexts(vod: Vod) {
    Text(
        text = vod.name,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = Spacing.sm, start = Spacing.xxs, end = Spacing.xxs),
    )
    Text(
        text = vodHeatText(vod),
        style = MaterialTheme.typography.labelMedium,
        color = starveilExtraColors.textFaint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = Spacing.xxs, start = Spacing.xxs, end = Spacing.xxs),
    )
}

/**
 * 左上角名次水印：顶部压一层海报叠字渐变（两端颜色来自令牌，保证与首页同一口径），
 * 数字本身第一名用品牌金、其余用正文白，形成"冠军独占一个视觉权重"的层级。
 */
@Composable
private fun RankNumberScrim(rank: Int) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(starveilExtraColors.heroScrimEnd, starveilExtraColors.heroScrimStart),
                    ),
                ),
        )
        Text(
            text = rank.toString(),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = if (rank == FIRST_RANK) {
                starveilExtraColors.brandGold
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = Spacing.sm, top = Spacing.xs),
        )
    }
}

internal const val TOP_RANK_COUNT = 3

private const val FIRST_RANK = 1

