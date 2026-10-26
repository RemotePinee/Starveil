package com.starveil.feature.rank

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.starveil.core.designsystem.starveilExtraColors

/**
 * 榜单页唯一的竖版海报原子组件。
 *
 * `poster` 在后端可能为空（旧接口不少条目缺图），空值走 [MaterialTheme.colorScheme.surfaceVariant]
 * 占位而不是留白，保证卡片高度稳定。圆角由调用方通过 modifier 决定，本组件不做裁剪。
 */
@Composable
internal fun RankPoster(
    poster: String?,
    name: String,
    modifier: Modifier = Modifier,
) {
    if (poster.isNullOrBlank()) {
        Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant))
        return
    }
    AsyncImage(
        model = poster,
        contentDescription = name,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(starveilExtraColors.surfaceCard),
    )
}

/** 竖版海报固定 2:3，TOP3 大卡与横排列表共用同一个比例。 */
internal const val RANK_POSTER_RATIO = 2f / 3f
