package com.starveil.feature.home

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.Vod

/**
 * 顶部主推位：可左右滑的海报轮播，左右各露一点相邻页。
 *
 * 叠字规则：标题压在底部渐变上（而不是放在图下面），这样海报复用竖版图也不会浪费高度；
 * 渐变两端来自令牌层，保证六套皮肤观感一致。
 */
@Composable
fun HomeHero(
    items: List<Vod>,
    onVodClick: (Vod) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        return
    }
    val featured = items.take(MAX_FEATURED)
    val pagerState = rememberPagerState(pageCount = { featured.size })
    // 轮播自动翻页动画被打断（切后台/被系统回收）时会停在两页之间，
    // 回来就变成"半张旧图半张新图"。ON_RESUME 时吸附回最近的整页。
    // 组合进来就立即吸附到最近的整页：分页偏移会被 saver 记住（切后台时被打断的
    // 自动翻页会留下 0.4 这类偏移），恢复后就成了"半张旧图半张新图"。
    LaunchedEffect(pagerState.currentPage) { pagerState.scrollToPage(pagerState.currentPage) }

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = Spacing.none,
        ) { page ->
            HeroCard(vod = featured[page], onClick = { onVodClick(featured[page]) })
        }
        Spacer(Modifier.height(Spacing.sm))
        PagerDots(count = featured.size, current = pagerState.currentPage)
    }
}

@Composable
private fun HeroCard(vod: Vod, onClick: () -> Unit) {
    LaunchedEffect(vod.poster) { HomeTiming.mark("hero-card-compose") }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESS_SCALE else 1f,
        animationSpec = tween(Motion.ShortMs),
        label = "hero-press",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(HERO_RATIO)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(AppShape.large)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
    ) {
        val heroStart = remember(vod.poster) { android.os.SystemClock.elapsedRealtime() }
        AsyncImage(
            model = vod.poster,
            contentDescription = vod.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().onGloballyPositioned { HomeTiming.since(heroStart, "hero-layout") },
            onSuccess = { HomeTiming.since(heroStart, "hero-image-ok") },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to starveilExtraColors.heroScrimStart,
                        0.55f to starveilExtraColors.heroScrimStart,
                        1f to starveilExtraColors.heroScrimEnd,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(Spacing.lg),
        ) {
            Text(
                text = vod.name,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = metaLine(vod),
                style = MaterialTheme.typography.bodySmall,
                color = starveilExtraColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PagerDots(count: Int, current: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val active = index == current
            Box(
                modifier = Modifier
                    .padding(horizontal = Spacing.xxs)
                    .height(Spacing.xs)
                    .width(if (active) Spacing.lg else Spacing.xs)
                    .clip(AppShape.full)
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.onBackground
                        } else {
                            starveilExtraColors.textFaint
                        },
                    ),
            )
        }
    }
}

/** 海报价下方的元信息：年份 · 地区 · 更新备注，空字段自动跳过。 */
internal fun metaLine(vod: Vod): String = listOfNotNull(
    vod.year?.takeIf { it.isNotBlank() },
    vod.area?.takeIf { it.isNotBlank() },
    vod.remarks?.takeIf { it.isNotBlank() },
).joinToString(" · ")

private const val MAX_FEATURED = 5
private const val HERO_RATIO = 0.78f

/** 按压回弹：优酷列表卡是 0.96~0.98 的轻缩放，不做夸张弹性。 */
private const val PRESS_SCALE = 0.98f
