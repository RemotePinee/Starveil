package com.starveil.feature.home

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.FilmStrip
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.model.HomeBanner

/**
 * 轮播的一页：只有图。
 *
 * 顶部/底部过渡与标题都不在这里——它们放在 [BannerHero] 那一层。
 * 卡片内部的 Box 拿到的高度约束不可靠（pager 页面里），`fillMaxHeight` 会算成 0，
 * 渐变层看着写了其实没画出来，这是之前怎么调都没有过渡的原因。
 */
@Composable
internal fun BannerCard(banner: HomeBanner, onClick: () -> Unit) {
    val pageBackground = MaterialTheme.colorScheme.background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(starveilExtraColors.surfaceCard)
            .clickable(onClick = onClick),
    ) {
        SubcomposeAsyncImage(
            model = banner.pic,
            contentDescription = banner.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        ) {
            val imageState by painter.state.collectAsState()
            if (imageState is AsyncImagePainter.State.Success) {
                SubcomposeAsyncImageContent()
            } else {
                // 图没加载出来只做一层中性占位，**不显示任何文案**：
                // 卡片本身（标题/副标题/点击）是正常的，"暂无推荐内容"那种
                // "后台没配"的语气会让人以为整块坏了。
                BannerImageFallback()
            }
        }
        // 底部过渡画在卡片内部：这里 fillMaxSize 一定等于卡片，渐变终点必然落在底边。
        // 放到上一层（BannerHero 的 Box 里）试过，那层始终没画出来，
        // 底边就露出一条下面那层的海报——深色下不明显，浅色白底上一眼就看见。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        BOTTOM_FADE_START to Color.Transparent,
                        1f to pageBackground,
                    ),
                ),
        )
    }
}

/**
 * 轮播图未就绪时的底：一层柔和渐变 + 一颗淡星，没有任何文案。
 *
 * 只在"已经有 banner 数据、但这张图没加载出来"时出现——它不该像空状态那样
 * 解释"去后台配置"，用户此时只想知道图为什么没出来，而卡片是可点击可用的。
 */
@Composable
private fun BannerImageFallback() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to starveilExtraColors.surfaceElevated,
                    1f to starveilExtraColors.surfaceCard,
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = PhosphorIcons.Regular.FilmStrip,
            contentDescription = null,
            tint = starveilExtraColors.textFaint.copy(alpha = 0.35f),
            modifier = Modifier.size(IconSize.lg),
        )
    }
}

/** 底部过渡起点：给标题与圆点留足空间，收得短就是一条硬边。 */
private const val BOTTOM_FADE_START = 0.45f
