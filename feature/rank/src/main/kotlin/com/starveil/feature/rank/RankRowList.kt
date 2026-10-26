package com.starveil.feature.rank

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.Vod

/**
 * 榜单主体：TOP3 大卡 + 第 4 名起的横排列表 + 翻页 footer，同一个 LazyColumn 承载，
 * 保证滚动连续，不会出现"大卡区单独滚 / 列表单独滚"的双滚动条。
 */
@Composable
internal fun RankBoardList(
    board: RankBoardState.Content,
    actions: RankActions,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.pageHorizontal,
            end = Spacing.pageHorizontal,
            top = Spacing.md,
            bottom = Spacing.lg,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        rankItems(board = board, actions = actions)
    }
}

/** 三段内容（TOP3 大卡、第 4 名起的横排行、footer）按顺序进同一个列表。 */
private fun LazyListScope.rankItems(
    board: RankBoardState.Content,
    actions: RankActions,
) {
    item(key = RANK_TOP_KEY) {
        RankTopRow(items = board.items, onVodClick = actions.onVodClick)
    }
    itemsIndexed(items = board.items.drop(TOP_RANK_COUNT), key = { _, vod -> vod.id }) { index, vod ->
        RankRowItem(
            rank = index + TOP_RANK_COUNT + 1,
            vod = vod,
            onClick = { actions.onVodClick(vod.id) },
        )
    }
    item(key = RANK_FOOTER_KEY) {
        RankFooter(
            board = board,
            onLoadMore = actions.onLoadMore,
            onRetry = actions.onRetry,
        )
    }
}

/**
 * 第 4 名起的横排行：名次（定宽右对齐，两位数不跳版）、小海报、名称 / 热度 / 地区年份。
 */
@Composable
private fun RankRowItem(
    rank: Int,
    vod: Vod,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShape.small)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RankBadge(rank = rank)
        RankRowPoster(vod = vod)
        Column(
            modifier = Modifier
                .weight(ROW_TEXT_WEIGHT)
                .padding(start = Spacing.md),
        ) {
            RankTexts(vod = vod)
        }
    }
}

@Composable
private fun RankBadge(rank: Int) {
    Text(
        text = rank.toString(),
        style = MaterialTheme.typography.titleMedium,
        color = starveilExtraColors.textFaint,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(Spacing.xxl),
    )
}

/** 小海报：权重、比例、圆角、底色都在这里定，真实海报直接套这个 modifier。 */
@Composable
private fun RowScope.RankRowPoster(vod: Vod) {
    RankPoster(
        poster = vod.poster,
        name = vod.name,
        modifier = Modifier
            .weight(ROW_POSTER_WEIGHT)
            .aspectRatio(RANK_POSTER_RATIO)
            .clip(AppShape.small)
            .background(starveilExtraColors.surfaceCard),
    )
}

@Composable
private fun RankTexts(vod: Vod) {
    Text(
        text = vod.name,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        text = vodHeatText(vod),
        style = MaterialTheme.typography.bodySmall,
        color = starveilExtraColors.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = Spacing.xxs),
    )
    val detail = vodDetailText(vod)
    if (detail.isNotEmpty()) {
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            color = starveilExtraColors.textFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.xxs),
        )
    }
}

/**
 * 列表底部：翻页失败给重试，加载中给进度，到底给文案。
 * 其余情况把 footer 的首次组合当作触底信号，向外抛 [onLoadMore]。
 */
@Composable
private fun RankFooter(
    board: RankBoardState.Content,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        when {
            board.appendError != null -> TextButton(onClick = onRetry) {
                Text("加载失败，点击重试")
            }

            board.isAppending -> CircularProgressIndicator()
            board.endReached -> Text(
                text = "没有更多了",
                style = MaterialTheme.typography.bodySmall,
                color = starveilExtraColors.textFaint,
            )

            else -> LaunchedEffect(board.items.size) { onLoadMore() }
        }
    }
}

private const val RANK_TOP_KEY = "rank_top"
private const val RANK_FOOTER_KEY = "rank_footer"

/** 橫排列表里海报与文字的宽度配比（按权重而非裸 dp 分配，窄屏也不挤）。 */
private const val ROW_POSTER_WEIGHT = 1.4f
private const val ROW_TEXT_WEIGHT = 4.6f

