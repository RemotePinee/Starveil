package com.starveil.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import com.starveil.core.common.ad.AdPlacement
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing

/** 横幅 / 暂停贴片默认比例。 */
const val AD_WIDE_RATIO: Float = 16f / 9f

/** 信息流卡比例：整行插播，比横幅矮一点，避免一屏被一条广告吃掉。 */
const val AD_FEED_RATIO: Float = 3.2f

/**
 * 直投广告素材卡：图片 + 「广告」角标 + 可选标题。
 *
 * 无素材时**不要调用**（先判 `AdPlacement.drawable`）：这里没有骨架/失败态，
 * 空图就是一块灰，占位反而比不展示更难看。点击带上落地页由调用方转给 [LocalAdClick]。
 */
@Composable
fun AdCreativeCard(
    creative: AdPlacement,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    aspectRatio: Float = AD_WIDE_RATIO,
) {
    val colors = starveilExtraColors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(AppShape.medium)
            .background(colors.surfaceCard)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = creative.imageUrl,
            contentDescription = creative.title.ifBlank { "广告" },
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        AdBadge(modifier = Modifier.align(Alignment.TopStart).padding(Spacing.sm))
        if (creative.title.isNotBlank()) {
            Text(
                text = creative.title,
                style = MaterialTheme.typography.bodySmall,
                // 压在素材图上：固定白，浅色主题下不能跟着变成深色
                color = colors.onImage,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(colors.heroScrimEnd, colors.heroScrimStart)))
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            )
        }
    }
}
