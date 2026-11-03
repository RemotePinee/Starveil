package com.starveil.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import com.starveil.core.designsystem.component.SkeletonBlock
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.Vod

/** 区块标题：左侧主标题 + 右侧可选的"更多"。 */
@Composable
fun SectionTitle(
    title: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (action != null) {
            Text(
                text = action,
                style = MaterialTheme.typography.bodySmall,
                color = starveilExtraColors.textFaint,
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
    }
}

/** 两列大卡（编辑精选），标题压在图上，图下一条副标题。 */
@Composable
fun EditorialRow(
    items: List<Vod>,
    onVodClick: (Vod) -> Unit,
) {
    val pair = items.take(EDITORIAL_COLUMNS)
    if (pair.isEmpty()) {
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        pair.forEach { vod ->
            EditorialCard(vod = vod, onClick = { onVodClick(vod) })
        }
    }
}

/** 编辑精选的一张：图 + 图下副标题。 */
@Composable
private fun RowScope.EditorialCard(vod: Vod, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick),
    ) {
        PosterWithTitle(
            vod = vod,
            ratio = EDITORIAL_RATIO,
            titleStyle = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = vod.blurb?.takeIf { it.isNotBlank() } ?: vod.remarks.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = starveilExtraColors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 热度榜：一行一张横滑，后台配几条就出几条。 */
@Composable
fun RankRow(
    items: List<Vod>,
    onVodClick: (Vod) -> Unit,
) {
    if (items.isEmpty()) {
        return
    }
    // 横向滚动：后台配几条就出几条，不再截到一行三张。
    LazyRow(
        contentPadding = PaddingValues(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        itemsIndexed(items = items, key = { _, vod -> vod.id }) { index, vod ->
            RankCard(
                vod = vod,
                rank = index + 1,
                onClick = { onVodClick(vod) },
            )
        }
    }
}

/** 榜单的一张：海报 + 片名 + 集数。 */
@Composable
private fun RankCard(vod: Vod, rank: Int, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(RANK_CARD_WIDTH)
            .clickable(onClick = onClick),
    ) {
        Box {
            PosterWithTitle(
                vod = vod,
                ratio = POSTER_RATIO,
                titleStyle = MaterialTheme.typography.titleMedium,
                showTitle = false,
            )
            Badge(
                text = "TOP$rank",
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(Spacing.xs),
            )
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = vod.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = vod.remarks.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = starveilExtraColors.textFaint,
            maxLines = 1,
        )
    }
}

/** 普通海报网格：按列数把列表切成行，避免在 LazyColumn 里嵌 LazyVerticalGrid。 */
@Composable
fun PosterRows(
    items: List<Vod>,
    columns: Int,
    onVodClick: (Vod) -> Unit,
) {
    val safeColumns = columns.coerceIn(1, MAX_COLUMNS)
    Column(
        modifier = Modifier.padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        items.chunked(safeColumns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                row.forEach { vod ->
                    PosterCell(vod = vod, onClick = { onVodClick(vod) })
                }
                // 最后一行不足列数时补空位，保持卡片宽度一致
                repeat(safeColumns - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** 海报网格的一格：海报 + 片名，比例与圆角跟真实海报一致。 */
@Composable
private fun RowScope.PosterCell(vod: Vod, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = vod.poster,
            contentDescription = vod.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(POSTER_RATIO)
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

@Composable
private fun PosterWithTitle(
    vod: Vod,
    ratio: Float,
    titleStyle: TextStyle,
    showTitle: Boolean = true,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(AppShape.medium),
    ) {
        AsyncImage(
            model = vod.poster,
            contentDescription = vod.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.6f to starveilExtraColors.heroScrimStart,
                        1f to starveilExtraColors.heroScrimEnd,
                    ),
                ),
        )
        if (showTitle) {
            Text(
                text = vod.name,
                style = titleStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(Spacing.md),
            )
        }
    }
}

/** 小角标（TOP / 独播 / 会员）。 */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = AppShape.small,
        color = starveilExtraColors.badge,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
        )
    }
}

private const val EDITORIAL_RATIO = 0.72f

/** 竖版海报宽高比；骨架也照它摆，切分类时布局不会跳一下。 */
internal const val POSTER_RATIO = 0.66f

/** 行内最多摆几张：列数固定，后台数据只切分不改变排布。 */
private const val EDITORIAL_COLUMNS = 2
private const val MAX_COLUMNS = 4

/** 榜单卡宽度：三列时的单格宽，横向滚动时右侧露出下一张，提示还能滑。 */
private val RANK_CARD_WIDTH: Dp = Spacing.xxl * 3 + Spacing.lg

/** 占位行摆几张：一行三张与真实榜单首屏一致，换数据时高度不变。 */
private const val RANK_PLACEHOLDER_CELLS = 3
@Composable
private fun PlaceholderBlock(modifier: Modifier) {
    // 用全站统一的呼吸骨架：一块静止灰和「加载失败」长得一样
    SkeletonBlock(modifier = modifier)
}

/** 热度榜空窗期占位：尺寸与一行卡片一致，数据到了原地替换，下面区块不会跳。 */
@Composable
fun RankRowPlaceholder() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        repeat(RANK_PLACEHOLDER_CELLS) {
            Column(modifier = Modifier.width(RANK_CARD_WIDTH)) {
                PlaceholderBlock(modifier = Modifier.aspectRatio(POSTER_RATIO))
                Spacer(Modifier.height(Spacing.xs))
                PlaceholderBlock(
                    modifier = Modifier
                        .fillMaxWidth(0.82f)
                        .height(Spacing.md),
                )
                Spacer(Modifier.height(Spacing.xxs))
                PlaceholderBlock(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(Spacing.sm),
                )
            }
        }
    }
}

/** 猜你喜欢空窗期占位：一整行海报格，高度按当前列数算。 */
@Composable
fun PosterRowsPlaceholder(columns: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        repeat(columns.coerceIn(1, MAX_COLUMNS)) {
            PlaceholderBlock(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(POSTER_RATIO),
            )
        }
    }
}
