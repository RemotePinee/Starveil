package com.starveil.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.starveil.core.designsystem.component.SectionHeader
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.Vod

/**
 * 详情页底部的「相关推荐」横滑区。
 *
 * 旧页把推荐做成第三个 tab，
 * 列表适配器见 `:936-966`：海报 + 片名 + 更新文案，点击进详情。
 * 不再分 tab，直接排在选集下面；点击只上抛影片 id，导航由 :app 决定。
 */
@Composable
internal fun DetailRecommendations(
    vods: List<Vod>,
    loading: Boolean,
    onSelect: (Int) -> Unit,
) {
    // 加载中用骨架卡（与真实卡片同宽同比例），不整块转圈：转圈会撑高一行又缩回去
    if (vods.isEmpty() && !loading) return
    Column(modifier = Modifier.padding(top = Spacing.sm)) {
        SectionHeader(title = "相关推荐")
        if (vods.isEmpty()) {
            RecommendSkeleton()
            return@Column
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            items(items = vods) { vod ->
                RecommendCard(
                    vod = vod,
                    onClick = { onSelect(vod.id) },
                    modifier = Modifier.fillParentMaxWidth(CARD_WIDTH_FRACTION),
                )
            }
        }
    }
}

/** 单张推荐卡片：竖版海报，下面是片名与更新文案。 */
@Composable
private fun RecommendCard(
    vod: Vod,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.clickable(onClick = onClick)) {
        AsyncImage(
            model = vod.poster,
            contentDescription = vod.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(POSTER_ASPECT_RATIO)
                .clip(AppShape.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        CardText(vod = vod)
    }
}

/** 片名 + 更新文案。 */
@Composable
private fun CardText(vod: Vod) {
    Text(
        text = vod.name,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = Spacing.xs),
    )
    val remarks = vod.remarks.orEmpty()
    if (remarks.isNotBlank()) {
        Text(
            text = remarks,
            style = MaterialTheme.typography.bodySmall,
            color = starveilExtraColors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
/**
 * 推荐区骨架：3 张竖版占位，尺寸与 [RecommendCard] 完全一致。
 *
 * 不用整块 CircularProgressIndicator：转圈高度只有一行，数据回来会再撑成海报高，
 * 页面会跳一下；骨架直接占住最终高度。
 */
@Composable
private fun RecommendSkeleton() {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Spacing.pageHorizontal),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        items(SKELETON_COUNT) {
            Column(modifier = Modifier.fillParentMaxWidth(CARD_WIDTH_FRACTION)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(POSTER_ASPECT_RATIO)
                        .clip(AppShape.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Box(
                    modifier = Modifier
                        .padding(top = Spacing.sm)
                        .fillMaxWidth(SKELETON_TITLE_FRACTION)
                        .height(Spacing.md)
                        .clip(AppShape.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
    }
}

private const val SKELETON_COUNT = 3
private const val SKELETON_TITLE_FRACTION = 0.7f

/** 一屏露 3 张多一点，横滑时有下一张的边缘提示。 */
private const val CARD_WIDTH_FRACTION = 0.3f

/** 竖版海报；比例不是视觉令牌，只有颜色/间距/圆角必须走令牌。 */
private const val POSTER_ASPECT_RATIO = 2f / 3f
