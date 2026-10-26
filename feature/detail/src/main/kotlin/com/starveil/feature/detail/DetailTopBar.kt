package com.starveil.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.starveil.core.designsystem.mirrorIconInRtl
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing

/**
 * 详情页顶栏：返回键浮在头图上，标题随滚动吸顶（§18.3）。
 *
 * 折叠进度直接吃列表的滚动量（[LazyListState.firstVisibleItemScrollOffset] 除以头图高度），
 * 不额外接 NestedScroll——只在顶栏自己的组合里读这个值，滚动时不会让整页重组。
 * 底色与图标色跟着同一个进度从"透明白"过渡到"实底 + 常规色"，与手指同步，不额外加动画。
 *
 * 收藏键已迁到视频下方的标题区（[DetailSummary]）：视频上的顶栏只留返回，
 * 不再有第二个浮在剧照上的按钮。
 *
 * 状态栏留白由 [DetailScreen] 统一给：顶栏与头图一起下移，不会互相错位。
 *
 * @param title 片名；null 表示还没拿到影片，顶栏固定实底，只留返回键
 */
@Composable
internal fun DetailTopBar(
    title: String?,
    listState: LazyListState,
    heroHeightPx: Int,
    onBack: () -> Unit,
) {
    val collapsed by remember(listState, heroHeightPx, title) {
        derivedStateOf { collapseFraction(listState, heroHeightPx, solid = title == null) }
    }
    val titleAlpha = ((collapsed - TITLE_FADE_START) / (1f - TITLE_FADE_START)).coerceIn(0f, 1f)
    val iconTint = lerp(
        MaterialTheme.colorScheme.onPrimary,
        MaterialTheme.colorScheme.onSurface,
        collapsed,
    )
    val iconScrim = (1f - collapsed) * TOP_ACTION_SCRIM_ALPHA
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = collapsed))
            .height(TOP_BAR_HEIGHT)
            .padding(horizontal = Spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(TOP_ACTION_SIZE)
                .clip(AppShape.full)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = iconScrim))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = PhosphorIcons.Regular.ArrowLeft,
                contentDescription = "返回",
                tint = iconTint,
                modifier = Modifier.size(IconSize.md).mirrorIconInRtl(),
            )
        }
        if (!title.isNullOrBlank() && titleAlpha > 0f) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = titleAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = Spacing.sm),
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

/**
 * 折叠进度 0..1：0 = 头图完整可见（顶栏透明），1 = 头图滚过顶栏（顶栏实底）。
 *
 * 头图滚出可视区后列表的 firstVisibleItemIndex 就不再是 0，此时直接拉满，
 * 避免"滚到很下面又变回半透明"。
 */
private fun collapseFraction(listState: LazyListState, heroHeightPx: Int, solid: Boolean): Float {
    val scrolled = when {
        solid || listState.firstVisibleItemIndex > 0 -> 1f
        heroHeightPx <= 0 -> 0f
        else -> listState.firstVisibleItemScrollOffset.toFloat() / heroHeightPx
    }
    return scrolled.coerceIn(0f, 1f)
}

/** 顶栏高度 56dp：32dp 图标 + 上下各 12dp 呼吸位。 */
private val TOP_BAR_HEIGHT: Dp = IconSize.lg + Spacing.xl

/** 图标圆形点击区，与播放器顶栏的返回键同档。 */
private val TOP_ACTION_SIZE: Dp = Spacing.xxl + Spacing.sm

/** 头图上图标的暗底浓度：只压暗图标周围一圈，不动海报本身。 */
private const val TOP_ACTION_SCRIM_ALPHA = 0.35f

/** 标题在折叠过半之后才开始显形，前半段仍以头图上的片名为准。 */
private const val TITLE_FADE_START = 0.5f
