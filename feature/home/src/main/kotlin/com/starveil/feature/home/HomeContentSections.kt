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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.FilmStrip
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
    ) {
        // 圆角定在容器上，而不是每张卡片自己裁：滑动时页面在容器内部平移，
        // 圆角属于容器、始终完整，不会被相邻页盖住也不会在边缘被裁掉。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(BANNER_RATIO)
                .clip(AppShape.large),
        ) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                BannerCard(banner = pages[page], onClick = { onClick(pages[page]) })
            }
            // 指示器直接压在剧照上，不再增加胶囊底。
            Row(
                modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                repeat(pages.size) { index ->
                    val active = index == pager.currentPage
                    Box(
                        modifier = Modifier
                            .height(Spacing.xs)
                            .width(if (active) Spacing.lg else Spacing.xs)
                            .clip(AppShape.full)
                            .background(
                                if (active) {
                                    starveilExtraColors.onImage
                                } else {
                                    starveilExtraColors.onImage.copy(alpha = DOTS_INACTIVE_ALPHA)
                                },
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun BannerCard(banner: HomeBanner, onClick: () -> Unit) {
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
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
                text = banner.title,
                style = MaterialTheme.typography.titleLarge,
                // 压在剧照上：固定白，不能跟主题（浅色下会变深、直接看不见）
                color = starveilExtraColors.onImage,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (banner.subtitle.isNotBlank()) {
                Text(
                    text = banner.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = starveilExtraColors.onImage.copy(alpha = ON_IMAGE_MUTED),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
 * 尺寸与 [BannerCard] 完全一致（同样的 16:9 全宽 + [BANNER_RATIO]）：
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
            .padding(horizontal = Spacing.lg)
            .aspectRatio(BANNER_RATIO)
            .clip(AppShape.large)
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

/** 自动轮播间隔：比常见的 3s 慢一点，阅读标题不被打断。 */
private const val BANNER_AUTO_MS = 4_500L

/** 图上的次级文字（副标题）透明度：白字压图时略降一档，层级才分得开。 */
private const val ON_IMAGE_MUTED = 0.78f


/** 非选中圆点的透明度。 */
private const val DOTS_INACTIVE_ALPHA = 0.45f
