package com.starveil.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Precision
import com.starveil.core.designsystem.R
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import kotlinx.coroutines.delay

/** 占位里那颗品牌标占卡片宽度的比例：再大就抢戏，再小看不出是什么。 */
private const val WATERMARK_FRACTION = 0.34f

/** 品牌标的不透明度：只做"纸的水印"，不参与阅读。 */
private const val WATERMARK_ALPHA = 0.09f

/** 扫光一个来回的时长：慢一点才像光，快了像加载条。 */
private const val SHIMMER_MS = 1600

/** 图回来之后占位还留一会儿：让 crossfade 有东西可叠，不要一刀切走留个空档。 */
private const val PLACEHOLDER_HOLD_MS = 380L

/** 扫光在浅色底上的强度：太淡看不见，太亮像一道油光。 */
private const val SWEEP_ALPHA_LIGHT = 0.42f

/** 扫光在深色底上的强度。 */
private const val SWEEP_ALPHA_DARK = 0.10f

/**
 * 全站唯一的影片封面图：品牌占位 + 扫光 + 远程图。
 *
 * 占位不是一块灰：卡片底色上压一颗品牌标水印，一条斜向光带慢慢扫过——
 * 读起来是"这张卡片在等它的图"，而不是"这里有个洞"。
 * 图回来走 Coil 的 crossfade 盖上去，扫光停、占位再退场，中间没有空帧。
 *
 * 所有列表/网格/榜单/推荐位的封面都用它，别在页面里各写一份 `AsyncImage`——
 * 榜单、剧场、专题、播放页推荐、收藏与历史必须是同一张"等待中的卡片"。
 *
 * 只给**内容封面**用（海报、剧集缩略图、专题/直播封面）。整屏轮播、详情页大背景、
 * 开屏广告、二维码这类装饰性或非影片内容不垫：它们的空位本来就该是背景本身。
 *
 * 传空 URL 时只画占位、不发请求——后端缺图的条目也不会留下一个洞。
 */
@Composable
public fun PosterImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = AppShape.small,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    // 显式设 INEXACT：内存缓存键是 URL（Coil 的 UriKeyer 只取 data），
    // 预热时按格子尺寸解好的位图能被卡片直接复用，只要它不比卡片要的小。
    val request = remember(context, url) {
        url?.takeIf(String::isNotBlank)?.let { link ->
            ImageRequest.Builder(context)
                .data(link)
                .precision(Precision.INEXACT)
                .build()
        }
    }
    var loaded by remember(request) { mutableStateOf(false) }
    // 图到了也先留着占位：crossfade 是叠在它上面的，立刻撤掉会露出半透明的底
    var placeholder by remember(request) { mutableStateOf(true) }
    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        delay(PLACEHOLDER_HOLD_MS)
        placeholder = false
    }
    Box(modifier = modifier.clip(shape)) {
        if (placeholder) PosterPlaceholder(shape = shape)
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = contentScale,
                onState = { state -> if (state is AsyncImagePainter.State.Success) loaded = true },
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** 品牌占位：卡片底 + 水印标 + 斜向扫光。 */
@Composable
private fun BoxScope.PosterPlaceholder(shape: Shape) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val watermark = MaterialTheme.colorScheme.onBackground.copy(alpha = WATERMARK_ALPHA)
    val sweep = starveilExtraColors.onImage.copy(
        alpha = if (dark) SWEEP_ALPHA_DARK else SWEEP_ALPHA_LIGHT,
    )
    val transition = rememberInfiniteTransition(label = "poster-placeholder")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SHIMMER_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "poster-shimmer",
    )
    Box(
        modifier = Modifier
            .matchParentSize()
            .clip(shape)
            .background(starveilExtraColors.surfaceCard)
            .drawBehind {
                // 光带斜着扫过整张卡片：起点在左外侧，终点在右外侧
                val band = size.width * 0.75f
                val x = -band + progress * (size.width + band)
                drawRect(
                    brush = Brush.linearGradient(
                        0f to Color.Transparent,
                        0.5f to sweep,
                        1f to Color.Transparent,
                        start = Offset(x, 0f),
                        end = Offset(x + band, size.height),
                    ),
                )
            },
    ) {
        Image(
            painter = painterResource(R.drawable.brand_mark),
            contentDescription = null,
            colorFilter = ColorFilter.tint(watermark, BlendMode.SrcIn),
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(WATERMARK_FRACTION)
                .aspectRatio(1f),
        )
    }
}

/**
 * 底板本身：圆角裁切 + 卡片色。
 *
 * [PosterImage] 已经内置占位，这里单独公开是给"图外面还要叠角标/渐变/文字"的容器用——
 * 那种地方图片是 `Box` 的一个子项，底板要贴在外层容器上。
 */
@Composable
public fun Modifier.imagePlate(shape: Shape = AppShape.small): Modifier =
    clip(shape).background(starveilExtraColors.surfaceCard)
