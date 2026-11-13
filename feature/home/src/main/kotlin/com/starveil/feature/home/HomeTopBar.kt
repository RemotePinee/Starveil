package com.starveil.feature.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.VodType
import kotlinx.coroutines.delay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.lerp
import com.starveil.core.designsystem.token.Motion
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * 顶栏压暗的浓度。
 *
 * 不分明暗主题：这层压的是轮播剧照，跟页面底色无关。浅色主题早先用 0.45，
 * 结果白字压在一张亮剧照上就融进图里——"浅色主题想空一点"和"白字要读得清"是两件事，
 * 只能按后者定值。
 */
private const val TOP_SCRIM_ALPHA = 0.72f

/** 渐隐开始的位置（占顶栏高度的比例）：搜索行压满，分类行已经在往下走。 */
private const val TOP_SCRIM_HOLD = 0.72f

/** 顶栏底边留下的浓度：剩下的渐隐交给轮播抠洞接着做。 */
private const val TOP_SCRIM_HANDOFF = 0.30f

/** 背景亮度低于它就算深色。 */
private const val DARK_LUMINANCE = 0.5f

/**
 * 顶栏 / 状态栏那层压暗色。
 *
 * 分两层叠，不做单色插值：单色 lerp 的中段是"半透明灰"，压在剧照上就是一坨脏色。
 * 层一 = 剧照上的深色遮罩（浓度随进度退），层二 = 落地色（随进度进）。
 */
@Composable
private fun Modifier.topBarBackdrop(coverProgress: Float): Modifier {
    val dim = 1f - coverProgress
    val dark = starveilExtraColors.scrimStrong
    val landed = landedTopColor()
    val statusBarPx = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toFloat() }
    return drawBehind {
        // 往状态栏方向多画一条：顶栏和状态栏必须是同一块底。分成两处画，
        // 参数一旦不同步，顶部就是"上黑下白"两截（之前那条硬边的来源）。
        val top = -statusBarPx
        val fullHeight = size.height - top
        val area = Size(size.width, fullHeight)
        if (dim > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to dark.copy(alpha = TOP_SCRIM_ALPHA * dim),
                    TOP_SCRIM_HOLD to dark.copy(alpha = TOP_SCRIM_ALPHA * dim),
                    1f to dark.copy(alpha = TOP_SCRIM_ALPHA * TOP_SCRIM_HANDOFF * dim),
                    startY = top,
                    endY = top + fullHeight,
                ),
                topLeft = Offset(0f, top),
                size = area,
            )
        }
        if (coverProgress > 0f) {
            drawRect(
                color = landed.copy(alpha = coverProgress),
                topLeft = Offset(0f, top),
                size = area,
            )
        }
    }
}

/**
 * 顶栏落地以后的底色。
 *
 * 深色模式落比页面底色亮一档的容器色：黑遮罩 → #252525 只差一点，肉眼读不出
 * "顶栏已经坐实了"，抬一档才有可辨的变化，也不用画分隔线。
 */
@Composable
private fun landedTopColor(): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < DARK_LUMINANCE
    // 深色必须落在比页面底色亮足一档的卡片色上：surfaceElevated 只比 #252525 亮 5 个色阶，
    // 遮罩退、落地进这两步叠出来的亮度几乎不变，看着就是"啪"一下过去了。
    return if (dark) starveilExtraColors.surfaceCard else MaterialTheme.colorScheme.background
}

/**
 * 轮播卡片顶部抠洞里的压暗：接着顶栏底边的残余浓度淡到 0。
 *
 * 落到页面底色之后（coverProgress=1）跟着退干净，否则卡片顶上会留一层白纱。
 */
@Composable
internal fun holeScrimColor(coverProgress: Float): Color =
    starveilExtraColors.scrimStrong.copy(
        alpha = TOP_SCRIM_ALPHA * TOP_SCRIM_HANDOFF * (1f - coverProgress),
    )

/**
 * 触发的滚动距离：滑过这么多就直接切到"落地"态。
 *
 * 不再跟着滚动比例连续变——那样手指停在中途，顶栏就一直停在半透明灰的中间态上。
 * 现在是过阈值触发，剩下的交给动画，中间态只存在于那两百毫秒里。
 */
private val TOP_BAR_SOLID_THRESHOLD = Spacing.xxl

/**
 * 顶栏从"压在轮播图上"到"落在页面底色上"的进度。
 *
 * 只有两个状态：压在图上（白字 + 压暗）和落在底色上（深色字）。滚动一越过
 * [TOP_BAR_SOLID_THRESHOLD] 就往 1 走，回滚到阈值以上就往 0 走，中间由
 * [Motion.MediumMs] 的 tween 补完——所以它是"直接切、切得顺"，不留中间态。
 */
@Composable
internal fun rememberTopBarCoverProgress(scrollPx: Float): Float {
    val threshold = with(LocalDensity.current) { TOP_BAR_SOLID_THRESHOLD.toPx() }
    val progress by animateFloatAsState(
        targetValue = if (scrollPx > threshold) 1f else 0f,
        animationSpec = tween(Motion.MediumMs, easing = FastOutSlowInEasing),
        label = "top-bar-cover",
    )
    return progress
}
/**
 * 顶部栏：一行操作区（logo 占位 / 搜索 / 筛选），一行分类。
 *
 * 搜索和分类拆成两行是参考小米视频那套：分类是浏览的主路径，搜索是调用动作，
 * 挤在一行时总有一边要将就。操作区里三块横向按 1:弹性:固定 排，
 * 分类独占一行、超出就横向滚动（两端渐隐由 [CategoryChips] 负责）。
 */
@Composable
fun HomeTopBar(
    hotWord: String,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    types: List<VodType> = emptyList(),
    selectedTypeId: Int = 0,
    /** 0 = 压在轮播图上（白字 + 压暗），1 = 落在页面底色上（深色字）。 */
    coverProgress: Float = 0f,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .topBarBackdrop(coverProgress)
                // 分类行到轮播的距离：只收底部，顶部那 4dp 留着，搜索行不至于贴到状态栏。
                .padding(
                    start = Spacing.lg,
                    end = Spacing.md,
                    top = Spacing.xs,
                    bottom = Spacing.xxs,
                ),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandLogo(coverProgress)
                Spacer(Modifier.width(Spacing.sm))
                SearchPill(
                    hotWord = hotWord,
                    onSearch = actions.onSearch,
                    coverProgress = coverProgress,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Spacing.sm))
                FilterButton(onClick = actions.onFilter, coverProgress = coverProgress)
            }
            if (types.isNotEmpty()) {
                CategoryChips(
                    types = types,
                    selectedTypeId = selectedTypeId,
                    onSelect = actions.onSelectType,
                    modifier = Modifier.fillMaxWidth(),
                    leadingPadding = Spacing.xxs,
                    coverProgress = coverProgress,
                )
            }
        }
        // 落在页面底色上以后不画分隔线：那条 1dp 线在深色模式里比两侧都亮，
        // 直接从顶上割出一道横线。深色的"坐实"靠落地色比页面底色亮一档，
        // 浅色靠落到页面底色，两边都不需要线。
    }
}

/**
 * 左上角品牌图标。
 *
 * 图是带透明通道的 PNG（`drawable-nodpi/feature_home_logo.png`，192×192）：
 * 放 `nodpi` 是为了不被屏幕密度再缩一次把边缘糊掉；透明区直接透出页面背景，
 * 所以这里不加底板、也不裁圆角——形状由图片自己决定。
 */
@Composable
private fun BrandLogo(coverProgress: Float) {
    // 浅色模式下顶栏落到页面底色上时换成黑+粉那一版；深色模式的底色本身就是深的，白粉版才对
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < DARK_LUMINANCE
    val inkAlpha = if (darkTheme) 0f else coverProgress
    Box(modifier = Modifier.size(LogoSize)) {
        Image(
            painter = painterResource(R.drawable.feature_home_logo),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().alpha(1f - inkAlpha),
        )
        Image(
            painter = painterResource(R.drawable.feature_home_logo_ink),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().alpha(inkAlpha),
        )
    }
}

/**
 * 系统状态栏图标跟着顶栏一起切。
 *
 * 首页顶部是剧照，图标必须是浅色；轮播滚走后底下变成页面底色，浅色图标就看不见了。
 * 这里按同一个进度换：过半数就切，切早了会跟还没走完的图打架。
 */
@Composable
internal fun SyncStatusBarIcons(coverProgress: Float) {
    val view = LocalView.current
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < DARK_LUMINANCE
    val solid = coverProgress > 0.5f
    LaunchedEffect(solid, darkTheme) {
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
            !darkTheme && solid
    }
}

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/** 搜索胶囊：中间放热词（没有就写「搜索」），右端放大镜；按下缩一下再进搜索页。 */
@Composable
private fun SearchPill(
    hotWord: String,
    onSearch: () -> Unit,
    coverProgress: Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(SearchPillHeight)
                // 先裁圆再画底色：drawBehind 排在 clip 之前的话，矩形底色画在裁剪层外面，
                // 圆角就没了（上一版就是把胶囊弄成方块的）。
                .clip(AppShape.full)
                .then(pillBackdrop(coverProgress))
                .clickable(onClick = onSearch),
            shape = AppShape.full,
            color = Color.Transparent,
            contentColor = pillContent(coverProgress),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = hotWord.ifBlank { "搜索影片、演员" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = pillContent(coverProgress),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Spacing.xs))
                Icon(
                    imageVector = PhosphorIcons.Regular.MagnifyingGlass,
                    contentDescription = "搜索",
                    tint = pillContent(coverProgress),
                    modifier = Modifier.size(IconSize.sm),
                )
            }
        }
    }
}

/** 右端「筛选」：进片库筛选页，图标与文字都复用主题色，不新造一套按钮样式。 */
@Composable
private fun FilterButton(onClick: () -> Unit, coverProgress: Float) {
    Surface(
        modifier = Modifier
            .height(SearchPillHeight)
            // 同搜索胶囊：先裁圆，再让分层底色画在裁剪层里
            .clip(AppShape.full)
            .then(pillBackdrop(coverProgress))
            .clickable(onClick = onClick),
        shape = AppShape.full,
        color = Color.Transparent,
        contentColor = pillContent(coverProgress),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = Spacing.md),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "筛选",
                style = MaterialTheme.typography.bodyMedium,
                color = pillContent(coverProgress),
                maxLines = 1,
            )
        }
    }
}

/**
 * 胶囊底：两层交叉淡入，而不是把一个半透明色插值成实色。
 *
 * 单色 lerp 的中段是"半透明灰"：底下轮播正在滚，青色随时变，胶囊看起来就在跳。
 * 分层之后任意时刻都只有"玻璃压着实色"一种组合，滚动时颜色是连续的。
 */
@Composable
private fun pillBackdrop(coverProgress: Float): Modifier {
    val glass = starveilExtraColors.imageGlass
    val solid = starveilExtraColors.chipBackground
    return Modifier.drawBehind {
        drawRect(color = solid, alpha = coverProgress)
        drawRect(color = glass, alpha = 1f - coverProgress)
    }
}

/** 胶囊内容：压在剧照上是白字，落在页面底色上换成正文色。 */
@Composable
private fun pillContent(coverProgress: Float) =
    lerp(starveilExtraColors.onImage, MaterialTheme.colorScheme.onBackground, coverProgress)

/** 搜索胶囊与筛选按钮共用行高：操作区三个元素对齐到同一条基线。 */
internal val SearchPillHeight = Spacing.xxl + Spacing.sm

/** logo 占位方块边长，与搜索胶囊同高。 */
internal val LogoSize = SearchPillHeight
