package com.starveil.feature.home

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

/** 顶栏压暗：深色压得住状态栏白图标，浅色压一档就够。 */
private const val TOP_SCRIM_ALPHA = 0.72f
private const val TOP_SCRIM_ALPHA_LIGHT = 0.45f

/** 背景亮度低于它就算深色。 */
private const val DARK_LUMINANCE = 0.5f

/**
 * 顶栏渐隐的压暗色。
 *
 * 钉在顶栏上、不随内容滚：轮播图滑到状态栏/搜索行这一段时被它压住。
 * 轮播滚走后换成页面底色本身——顶栏"坐实"成一条背景，深色字永远有底，
 * 不会出现深字压在深色模糊图上的情况。
 */
@Composable
private fun topScrimBrush(coverProgress: Float): Brush {
    val dark = MaterialTheme.colorScheme.background.luminance() < DARK_LUMINANCE
    val base = if (dark) TOP_SCRIM_ALPHA else TOP_SCRIM_ALPHA_LIGHT
    val scrim = lerp(
        starveilExtraColors.scrimStrong.copy(alpha = base),
        MaterialTheme.colorScheme.background,
        coverProgress,
    )
    // 底边收成透明：顶栏和下面的内容之间不出现一条硬线
    return Brush.verticalGradient(0f to scrim, 1f to Color.Transparent)
}

/** 轮播滚完前一成开始变色：最后这点距离里字从白转深，滚到底正好读完。 */
private const val TOP_BAR_FADE_START = 0.9f

/**
 * 顶栏从"压在轮播图上"到"落在页面底色上"的进度。
 *
 * 轮播图滚出视口后，顶栏底下只剩页面底色，白字会直接看不见（浅色模式就是这样）；
 * 这里按轮播卡的高度算出 0→1 的进度：0 是压在图上（白字 + 压暗），
 * 1 是落在底色上（深色字、去掉压暗）。中间不做跳变，进度跟着滚动走，
 * 外加速度弹簧一样的 tween 兜住快速滑动，浅色模式的过渡才不"啪"一下。
 */
@Composable
internal fun rememberTopBarCoverProgress(listState: LazyListState, scrollPx: Float): Float {
    val bannerHeight = remember(listState) { mutableIntStateOf(0) }
    LaunchedEffect(listState) {
        // 轮播滚出视口后拿不到它的高度，所以只记住"看得见时"的那个值
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }?.size ?: 0 }
            .filter { it > 0 }
            .collect { bannerHeight.intValue = it }
    }
    val height = bannerHeight.intValue
    val target = if (height <= 0) {
        0f
    } else {
        val start = height * TOP_BAR_FADE_START
        val span = (height - start).coerceAtLeast(1f)
        ((scrollPx - start) / span).coerceIn(0f, 1f)
    }
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(Motion.MediumMs),
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
    val scrim = topScrimBrush(coverProgress)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(scrim)
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


/**
 * 状态栏那一条的压暗。
 *
 * 顶栏从状态栏下面开始，只压顶栏会留下最上面一条亮边；这里用同一个颜色补上，
 * 两块连成一片，滚动时也不会变。
 */
@Composable
internal fun StatusBarScrim(modifier: Modifier = Modifier, coverProgress: Float = 0f) {
    val dark = MaterialTheme.colorScheme.background.luminance() < DARK_LUMINANCE
    val base = if (dark) TOP_SCRIM_ALPHA else TOP_SCRIM_ALPHA_LIGHT
    val scrim = lerp(
        starveilExtraColors.scrimStrong.copy(alpha = base),
        MaterialTheme.colorScheme.background,
        coverProgress,
    )
    val height = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
    Box(modifier.fillMaxWidth().height(height).background(scrim))
}
