package com.starveil.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import coil3.compose.AsyncImage
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.model.Vod

/**
 * 头图：海报铺满 16:9，底部压一层渐变让下面的标题区接着读。
 *
 * 旧页头图是竖版 2:3（`item_play_info.xml`），顶部直接叠标题与返回键；
 * 改成横版构图，返回键交给 [DetailTopBar]，片名落到 [DetailSummary]，
 * 头图只负责画面本身。
 *
 * @param onHeight 把实测高度报给外层，[DetailTopBar] 用它算折叠进度
 *   （16:9 在不同宽高比屏上不是固定 dp，不能用常量代替）。
 */
@Composable
internal fun DetailHero(vod: Vod, onHeight: (Int) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(HERO_ASPECT)
            .onSizeChanged { onHeight(it.height) }
            .background(MaterialTheme.colorScheme.surfaceVariant),
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
                        0f to starveilExtraColors.heroScrimStart,
                        HERO_SCRIM_HOLD to starveilExtraColors.heroScrimStart,
                        1f to starveilExtraColors.heroScrimEnd,
                    ),
                ),
        )
    }
}

/** 头图比例。比例不是视觉令牌，只有颜色/间距/圆角必须走令牌。 */
internal const val HERO_ASPECT = 16f / 9f

/** 渐变到六成才开始压暗，保住海报中间的主体。 */
private const val HERO_SCRIM_HOLD = 0.6f
