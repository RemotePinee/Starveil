package com.starveil.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.regular.Heart
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.Vod
import com.starveil.core.designsystem.interaction.rememberHaptics
import com.starveil.core.designsystem.interaction.HapticKind

/**
 * 片名 + 评分 / 年份 / 地区 / 集数 + 右侧收藏键。
 *
 * 旧页这几项分散在头部布局的多个 TextView 里（`item_play_info.xml`），片名压在头图上；
 * 片名离开头图落到实底区域，长片名不会被海报的亮部吃掉，滚动后也由顶栏接管吸顶标题。
 * 收藏键跟着标题区一起滚：视频上的顶栏只留返回，收藏不再浮在画面上挡剧照。
 */
@Composable
internal fun DetailSummary(
    state: DetailUiState.Content,
    favorite: DetailFavoriteState,
    onToggleFavorite: () -> Unit,
    /** 后台「详情页 → 评分」关掉时不画那个数字，其余元信息照常。 */
    showScore: Boolean = true,
) {
    val vod = state.vod
    val meta = metaLine(vod, state.episodeCount)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.md),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = vod.name,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showScore) {
                    Text(
                        text = vod.score?.takeIf { it.isNotBlank() } ?: SCORE_FALLBACK,
                        color = starveilExtraColors.brandGold,
                    )
                }
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = starveilExtraColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (state.isRefreshing) {
                Text(
                    text = REFRESHING,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(Spacing.md))
        FavoriteButton(favorite = favorite, onClick = onToggleFavorite)
    }
}

/** 40dp 的心形键：已收藏用会员金实心，未收藏用弱化描边；请求在飞时点不动。 */
@Composable
private fun FavoriteButton(favorite: DetailFavoriteState, onClick: () -> Unit) {
    val favorited = favorite.isFavorited
    val haptics = rememberHaptics()
    Box(
        modifier = Modifier
            .size(FavoriteButtonSize)
            .clip(AppShape.full)
            .clickable(enabled = favorite !is DetailFavoriteState.Pending) {
                // 收藏是状态切换：给确认触感，取消收藏也一样
                haptics(HapticKind.Confirm)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (favorited) PhosphorIcons.Fill.Heart else PhosphorIcons.Regular.Heart,
            contentDescription = if (favorited) "取消收藏" else "收藏",
            tint = if (favorited) starveilExtraColors.brandGold else starveilExtraColors.textMuted,
            modifier = Modifier.size(IconSize.md),
        )
    }
}

/**
 * 简介默认收起 3 行。
 *
 * 「展开」只在真的被截断时出现：短简介配一个点了没反应的「展开」是假按钮。
 * 用 [androidx.compose.ui.text.TextLayoutResult.hasVisualOverflow] 判断，
 * 而不是按字符数猜（中英混排的行高差异很大）。没有简介时整块不渲染。
 */
@Composable
internal fun DetailBlurb(
    state: DetailUiState.Content,
    onToggle: () -> Unit,
) {
    val blurb = state.vod.blurb?.takeIf { it.isNotBlank() }
    if (blurb == null) return
    var overflowed by remember(blurb) { mutableStateOf(false) }
    val expanded = state.isBlurbExpanded
    // 三个条件并列写在一行会触发 detekt 的 ComplexCondition，先算成两个布尔
    val showExpand = !expanded && overflowed
    val showCollapse = expanded
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.sm),
    ) {
        BlurbTitle()
        Box(modifier = Modifier.padding(top = Spacing.xs)) {
            BlurbBody(
                blurb = blurb.orEmpty(),
                expanded = expanded,
                onOverflow = { overflowed = it },
            )
            // 「展开」叠在末行右侧，不额外占高度。之前另起一行，而 overflowed 要等第一帧
            // onTextLayout 才知道，于是"先 3 行 → 再补一行展开"把下面整块顶下去一下。
            if (showExpand) {
                Text(
                    text = "展开",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(MaterialTheme.colorScheme.background)
                        .padding(start = Spacing.xs)
                        .clickable(onClick = onToggle),
                )
            }
        }
        if (showCollapse) {
            Text(
                text = "收起",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = Spacing.xxs)
                    .clickable(onClick = onToggle),
            )
        }
    }
}

/** 简介标题：真实数据时是「简介」两字。 */
@Composable
private fun BlurbTitle() {
    Text(
        text = "简介",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** 正文：按展开状态限行。 */
@Composable
private fun BlurbBody(
    blurb: String,
    expanded: Boolean,
    onOverflow: (Boolean) -> Unit,
) {
    Text(
        text = blurb,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else BLURB_COLLAPSED_LINES,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result -> onOverflow(result.hasVisualOverflow) },
    )
}

/** 只拼有值的字段，缺一项不留「 · 」空档；集数放在最后，写法与旧页「更新至 N 集」区分开。 */
private fun metaLine(vod: Vod, episodeCount: Int): String = listOfNotNull(
    vod.year?.takeIf { it.isNotBlank() },
    vod.area?.takeIf { it.isNotBlank() },
    episodeCount.takeIf { it > 0 }?.let { "共 $it 集" },
    vod.remarks?.takeIf { it.isNotBlank() },
).joinToString(META_SEPARATOR)

private const val BLURB_COLLAPSED_LINES = 3

private val FavoriteButtonSize: Dp = Spacing.xxl + Spacing.sm

private const val SCORE_FALLBACK = "暂无评分"
private const val META_SEPARATOR = " · "
private const val REFRESHING = "正在刷新…"
