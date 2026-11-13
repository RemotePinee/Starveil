package com.starveil.feature.user

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.starveil.core.designsystem.component.PosterImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.ClockCounterClockwise
import com.starveil.core.designsystem.mirrorIconInRtl
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.ContinueWatching
import com.starveil.core.model.ContinueWatchingRules

/**
 * 观看记录区：横滑卡片，海报上直接叠进度条，不再是"一行文字链接"。
 *
 * 数据只有 `videoProgressList` 的播放位置（见 [ContinueWatching]），没有单集总时长，
 * 所以百分比按 [ContinueWatchingRules.percent] 的名义时长估算——与首页「继续观看」
 * 同一套口径，**只做展示**，不参与播放或试看判定。
 *
 * 卡片 3~4 张可见：宽 [CARD_WIDTH]，360dp 屏上露到第 4 张的一半，暗示可以往左滑。
 */
@Composable
internal fun UserCenterWatchHistory(input: WatchHistoryInput, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        HistoryHeader(onSeeAll = input.onSeeAll)
        // 与下方"常用工具"一致：标题到卡片留 Spacing.md
        Spacer(Modifier.height(Spacing.md))
        if (input.items.isEmpty()) {
            EmptyHistoryCard(hint = input.emptyHint, onClick = input.onEmptyClick)
            return@Column
        }
        val listState = rememberLazyListState()
        // 数据整批换掉时回到第一张。
        // LazyRow 会保留上一次的 index，从短剧切回来时观看记录已经换成另一批片，
        // 位置还停在旧的中段——看起来就是「自己滚到中间去了」。
        LaunchedEffect(input.items.firstOrNull()?.vodId) {
            listState.scrollToItem(0)
        }
        // 哪一端还有内容才擦哪一端：滚到最左时左端完整，滚到最右时右端完整。
        // 无条件擦两端会让「只有三张卡、根本不需要滚」的时候左边也灰掉一条。
        Box(
            modifier = Modifier.horizontalEdgeFade(
                fadeStart = listState.canScrollBackward,
                fadeEnd = listState.canScrollForward,
            ),
        ) {
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                contentPadding = PaddingValues(vertical = Spacing.xxs),
            ) {
                items(items = input.items, key = { it.vodId }) { item ->
                    HistoryCard(item = item, onClick = { input.onItemClick(item) })
                }
            }
        }
    }
}

/**
 * 横滑区两端渐隐（左、右各 [fadeWidth]）。
 *
 * 用 [BlendMode.DstOut] 把已画的内容按 alpha 擦成透明，而不是盖一层背景色：
 * 盖色在浅色模式下会融成一坨，深浅两套主题还得各配一份渐变色；擦掉之后透出的是
 * 页面本身的底色，跟首页分类行同一套做法。
 *
 * 只能挂在这一排卡片的**容器**上——挂到单张卡片上，每张卡的左右边缘都会被啃掉。
 */
@Composable
private fun Modifier.horizontalEdgeFade(
    fadeStart: Boolean = true,
    fadeEnd: Boolean = true,
    fadeWidth: Dp = Spacing.lg,
): Modifier {
    val density = LocalDensity.current
    // DstOut 只吃 alpha 通道，颜色的 RGB 不参与合成；取令牌里的擦除色，别再写字面量
    val mask = starveilExtraColors.fadeMask
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            // 宽度为 0 的帧没有可擦的东西；两端都不擦时整段跳过。
            // 拆成两步算：三元条件放一行会被 detekt 的 ComplexCondition 拦（阈值 3）。
            val anyFade = fadeStart || fadeEnd
            val fading = anyFade && size.width > 0f
            if (!fading) return@drawWithContent
            // 渐变的 stop 是比例，所以按当前容器宽度把 dp 折成分数
            val fade = (with(density) { fadeWidth.toPx() } / size.width)
                .coerceIn(0f, MAX_FADE_FRACTION)
            // 按「这一端还有没有内容」决定擦哪一头：滚到最左时左端不擦，
            // 滚到最右时右端不擦。DstOut 只吃 alpha，中间段用透明表示不擦。
            // stop 直接写成可变参数，不先拼数组再展开（展开会多一次整表拷贝）。
            val brush = when {
                fadeStart && fadeEnd -> Brush.horizontalGradient(
                    0f to mask,
                    fade to Color.Transparent,
                    1f - fade to Color.Transparent,
                    1f to mask,
                )
                fadeStart -> Brush.horizontalGradient(
                    0f to mask,
                    fade to Color.Transparent,
                    1f to Color.Transparent,
                )
                else -> Brush.horizontalGradient(
                    0f to Color.Transparent,
                    1f - fade to Color.Transparent,
                    1f to mask,
                )
            }
            drawRect(brush = brush, blendMode = BlendMode.DstOut)
        }
}

/** 区标题 + 右侧「全部」：入口固定指回观看记录页（有完整列表与删除）。 */
@Composable
private fun HistoryHeader(onSeeAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = TITLE,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(WEIGHT_FULL),
        )
        Text(
            text = ACTION_ALL,
            style = MaterialTheme.typography.labelMedium,
            color = starveilExtraColors.textMuted,
            modifier = Modifier
                .clip(AppShape.full)
                .clickable(onClick = onSeeAll)
                .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
        )
    }
}

@Composable
private fun HistoryCard(item: ContinueWatching, onClick: () -> Unit) {
    val percent = ContinueWatchingRules.percent(item.progressSec)
    Column(modifier = Modifier.width(CARD_WIDTH).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CARD_RATIO)
                .clip(AppShape.small)
                .background(starveilExtraColors.chipBackground),
        ) {
            val pic = item.vodPic
            if (!pic.isNullOrBlank()) {
                PosterImage(
                    url = pic,
                    contentDescription = item.vodName,
                    modifier = Modifier.fillMaxSize(),
                    shape = AppShape.small,
                )
            }
            // 进度条贴在海报底边：底色留一条暗槽，亮色段按百分比铺满
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(PROGRESS_TRACK)
                    .background(starveilExtraColors.playerControlBackground),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(percent / PERCENT_FULL)
                    .height(PROGRESS_TRACK)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = item.vodName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "第 ${item.episodeNumber} 集 · 已看 $percent%",
            style = MaterialTheme.typography.labelSmall,
            color = starveilExtraColors.textFaint,
            maxLines = 1,
        )
    }
}

/** 没有记录（或未登录）：一张引导卡，点了去记录页或弹登录层；不整块消失，避免区块忽高忽低。 */
@Composable
private fun EmptyHistoryCard(hint: String, onClick: () -> Unit) {
    Surface(
        shape = AppShape.extraLarge,
        color = starveilExtraColors.surfaceCard,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(
                imageVector = PhosphorIcons.Regular.ClockCounterClockwise,
                contentDescription = null,
                tint = starveilExtraColors.textMuted,
                modifier = Modifier.size(IconSize.item),
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.titleMedium,
                color = starveilExtraColors.textMuted,
                modifier = Modifier.weight(WEIGHT_FULL),
            )
            Icon(
                imageVector = PhosphorIcons.Regular.CaretRight,
                contentDescription = null,
                tint = starveilExtraColors.textFaint,
                modifier = Modifier.size(IconSize.sm).mirrorIconInRtl(),
            )
        }
    }
}

/** 卡片宽度：360dp 屏（扣掉页面左右各 16dp）一行露 3 张多一点。 */
private val CARD_WIDTH = Spacing.xxl * 3

/** 海报比例沿用首页「继续观看」的 1.6，横滑卡片的宽高在同一屏里对齐。 */
private const val CARD_RATIO = 1.6f

private val PROGRESS_TRACK = Spacing.xxs
private const val PERCENT_FULL = 100f
private const val WEIGHT_FULL = 1f

/** 渐隐最多吃掉半边：容器窄到不足两端各一份时，别让两侧的 stop 交叉。 */
private const val MAX_FADE_FRACTION = 0.5f
private const val TITLE = "观看记录"
private const val ACTION_ALL = "全部"
