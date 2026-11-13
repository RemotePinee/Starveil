package com.starveil.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil3.compose.AsyncImagePainter
import coil3.SingletonImageLoader
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.FilmStrip
import com.starveil.core.designsystem.component.prefetchBlurredCover
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.HomeBanner
import com.starveil.core.model.HomeLink

/** 后台配置的轮播。数据源是 `v1.main/banner`，不再是列表前 5 条。 */
@Composable
fun BannerHero(
    banners: List<HomeBanner>,
    onClick: (HomeBanner) -> Unit,
    /** 0 = 压在轮播图上，1 = 已经滚到页面底色上；抠洞里的压暗跟它一起退。 */
    coverProgress: Float = 0f,
) {
    val pages = banners
    if (pages.isEmpty()) {
        return
    }
    // 首页宿主已经把 pager 提到了外层（顶部底色要跟着同一页走）；单独复用本组件时才自建。
    val own = rememberPagerState(pageCount = { pages.size })
    val pager = LocalHomeBannerPager.current ?: own
    // 自动轮播：到点翻下一页，回到第一页时不留白。
    // 只在页面真的在前台时跑：否则离开首页后它还在后台每 3 秒滚一次整个 pager，
    // 切回首页时正好撞上这一拍，转场就卡住。
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycle == Lifecycle.State.RESUMED
    LaunchedEffect(pager, pages.size, resumed) {
        if (!resumed || pages.size <= 1) {
            return@LaunchedEffect
        }
        while (true) {
            delay(BANNER_AUTO_MS)
            if (!pager.isScrollInProgress) {
                pager.animateScrollToPage((pager.currentPage + 1) % pages.size)
            }
        }
    }
    // 贴边：两套模式同一套结构。标题、圆点都画在这一层——
    // 这里高度由 aspectRatio 定死，`fillMaxHeight` 才算得出真实像素。
    // 顶部抠洞做在图里：洞里透出来的是屏幕级那层模糊底，抠开才露得出来。
    val mask = starveilExtraColors.fadeMask
    val holeScrim = holeScrimColor(coverProgress)
    PrefetchBannerBlur(pages)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(BANNER_RATIO)
            .clipToBounds(),
    ) {
        // 抠洞背后**不铺自己那层模糊**：露出来的必须是屏幕级那层 [HomeBannerBackdrop]，
        // 它和顶栏底下是同一块图、同一次裁切。各铺各的会把同一张图裁成两个比例，
        // 接缝处颜色对不上，那条线怎么调浓度都消不掉。
        // 顶栏那条渐隐从洞顶接着往下淡：前半段降得快（别在边界处留一块暗），
        // 后半段慢慢收到 0，和下面清晰的剧照之间没有台阶。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(BANNER_TOP_FADE)
                .background(
                    Brush.verticalGradient(
                        0f to holeScrim,
                        0.45f to holeScrim.copy(alpha = holeScrim.alpha * 0.45f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .bannerTopMask(mask),
        ) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                BannerCard(banner = pages[page], onClick = { onClick(pages[page]) })
            }
        }
        BannerTitles(
            banner = pages[pager.currentPage.coerceAtMost(pages.lastIndex)],
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(Spacing.lg),
        )
        // 指示器直接压在剧照上，不再增加胶囊底。
        BannerDots(count = pages.size, current = pager.currentPage)
    }
}

/** 每页的模糊版先解好：切页那一瞬间抠洞里不能先黑一下再亮起来。 */
@Composable
private fun PrefetchBannerBlur(banners: List<HomeBanner>) {
    val pics = remember(banners) { banners.mapNotNull { it.pic.takeIf(String::isNotBlank) } }
    val context = LocalContext.current
    LaunchedEffect(pics) {
        val loader = SingletonImageLoader.get(context)
        pics.forEach { loader.prefetchBlurredCover(context, it) }
    }
}

/** 轮播指示器：直接压在剧照上，不增加胶囊底。 */
@Composable
private fun BannerDots(count: Int, current: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val active = index == current
            val dotColor = MaterialTheme.colorScheme.onBackground
            Box(
                modifier = Modifier
                    .height(Spacing.xs)
                    .width(if (active) Spacing.lg else Spacing.xs)
                    .clip(AppShape.full)
                    .background(
                        if (active) dotColor else dotColor.copy(alpha = DOTS_INACTIVE_ALPHA),
                    ),
            )
        }
    }
}

/** 唯一的轮播空态：占位卡与图片加载失败都复用它，避免出现两套文案/排版。 */
@Composable
private fun BannerEmptyState() {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = PhosphorIcons.Regular.FilmStrip,
                contentDescription = null,
                tint = starveilExtraColors.textFaint,
                modifier = Modifier.size(IconSize.lg),
            )
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = "暂无推荐内容",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "先在后台首页装修里添加轮播",
                style = MaterialTheme.typography.bodySmall,
                color = starveilExtraColors.textMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 轮播占位卡：后台还没配 banner（或 BANNER 开关开着但接口返回空）时不留黑块。
 *
 * 尺寸与 [BannerCard] 完全一致（同样是贴边的 16:9 全宽 + [BANNER_RATIO]）：
 * 底色用 surfaceElevated→surfaceCard 的渐变，比纯色更像"待装修"的占位。
 * [onClick] 为 null 时只是纯占位，不可点击。
 */
@Composable
fun BannerPlaceholder(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(BANNER_RATIO)
            .background(
                Brush.verticalGradient(
                    0f to starveilExtraColors.surfaceElevated,
                    1f to starveilExtraColors.surfaceCard,
                ),
            )
            .clickable(enabled = onClick != null) { onClick?.invoke() },
    ) {
        BannerEmptyState()
    }
}

/** 一行 2 个横向宽卡（参考短剧首页的双图并排），超过 2 个换行。 */
private const val LINKS_PER_ROW = 2

/**
 * 后台配置的快捷入口。
 *
 * 卡片是「背景图 + 标题 + 副标题」：`bg` 由后台填链接或上传，空图时退回纯色底。
 * 历史版本的图标是客户端内置枚举，运营改不了图，视觉也和海报墙不是一套，这次去掉。
 */
@Composable
fun HomeLinksRow(
    links: List<HomeLink>,
    onClick: (HomeLink) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        links.chunked(LINKS_PER_ROW).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                row.forEach { link ->
                    LinkCard(
                        link = link,
                        onClick = { onClick(link) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // 最后一行不足 4 个时补空位，卡片宽度与其它行一致
                repeat(LINKS_PER_ROW - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** 快捷入口卡片：背景图铺满，底部压一层渐变保证标题可读。 */
@Composable
private fun LinkCard(link: HomeLink, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(LINK_RATIO)
            .clip(AppShape.medium)
            .background(starveilExtraColors.chipBackground)
            .clickable(onClick = onClick),
    ) {
        if (link.bg.isNotBlank()) {
            SubcomposeAsyncImage(
                model = link.bg,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            ) {
                val imageState by painter.state.collectAsState()
                if (imageState is AsyncImagePainter.State.Success) {
                    SubcomposeAsyncImageContent()
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            LINK_SCRIM_START to starveilExtraColors.heroScrimStart,
                            LINK_SCRIM_END to starveilExtraColors.heroScrimEnd,
                        ),
                    ),
            )
        }
        // 浅色背景图上白字会糊：给标题/副标题加一层深色投影，再压渐变
        val shadow = Shadow(
            color = starveilExtraColors.scrimStrong,
            offset = Offset(2f, 2f),
            blurRadius = 6f,
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        ) {
            Text(
                text = link.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    shadow = shadow,
                ),
                color = starveilExtraColors.onImage,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (link.subtitle.isNotBlank()) {
                Text(
                    text = link.subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(shadow = shadow),
                    color = starveilExtraColors.onImage.copy(alpha = ON_IMAGE_MUTED),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 卡片比例：16:9 横向宽卡；宽度由每行 2 个等分决定，高度跟着算。 */
private const val LINK_RATIO = 16f / 9f

/** 背景图上的渐变起点/终点；没有图时这两层不渲染。 */
private const val LINK_SCRIM_START = 0.2f
private const val LINK_SCRIM_END = 1f

/** 轮播卡片与轮播占位共用的比例：16:9 传统横向大图 */
internal const val BANNER_RATIO = 16f / 9f

/** 顶部抠洞到哪里为止：卡片顶部 30%，洞够深，顶栏的深色才有地方淡出去。 */
private const val BANNER_TOP_FADE = 0.30f

/**
 * 顶部抠洞：卡片按 [BANNER_TOP_FADE] 淡出，露出背后的模糊底。
 *
 * 必须用 DstIn 真抠：背后是模糊图，盖一层纯色永远对不上色，
 * 看上去就是"一条遮罩"而不是图淡出去。
 */
private fun Modifier.bannerTopMask(mask: Color): Modifier = drawWithContent {
    drawContent()
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Transparent,
            BANNER_TOP_FADE to mask,
            1f to mask,
        ),
        blendMode = BlendMode.DstIn,
    )
}


/** 轮播标题：固定在卡片左下角，跟着当前页换。 */
@Composable
private fun BannerTitles(banner: HomeBanner, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = banner.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (banner.subtitle.isNotBlank()) {
            Text(
                text = banner.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 自动轮播间隔：比常见的 3s 慢一点，阅读标题不被打断。 */
private const val BANNER_AUTO_MS = 4_500L

/** 图上的次级文字（副标题）透明度：白字压图时略降一档，层级才分得开。 */
private const val ON_IMAGE_MUTED = 0.78f


/** 非选中圆点的透明度。 */
private const val DOTS_INACTIVE_ALPHA = 0.45f
