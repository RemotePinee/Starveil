package com.starveil.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starveil.core.common.ad.AdPlacement
import com.starveil.core.designsystem.component.AdCreativeCard
import com.starveil.core.designsystem.component.AD_WIDE_RATIO
import com.starveil.core.designsystem.component.LocalAdClick
import com.starveil.core.designsystem.component.LocalAdPlacements
import com.starveil.core.designsystem.component.SkeletonBlock
import com.starveil.core.designsystem.component.StarveilLoadingScreen
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.HomeModule
import com.starveil.core.model.VodType
import com.starveil.core.model.Vod

/**
 * 首页入口。
 *
 * 页面不再自己加状态栏内边距：`Scaffold` 已经按系统栏留过位置，之前再叠一层
 * `statusBarsPadding()` 导致顶部空出一大块（用户反馈"离状态栏太远"）。
 *
 * 空分类不再整页替换：顶栏和分类行始终保留，用户随时能切回其它分类。
 */
@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    actions: HomeActions = HomeActions(),
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeScreen(
        state = state,
        actions = actions.copy(
            onRetry = viewModel::refresh,
            onRefresh = viewModel::refresh,
            onSelectType = viewModel::selectType,
            // 专题/直播卡片先落到对应 Tab：:app 目前只接了 onShortcut，
            // 给出可用的默认跳转，等接线方补 onTopicClick/onLiveClick 再覆盖。
            onTopicClick = { actions.onShortcut(HomeShortcut.TOPIC) },
            onLiveClick = { actions.onShortcut(HomeShortcut.LIVE) },
        ),
        modifier = modifier,
    )
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    actions: HomeActions = HomeActions(),
    modifier: Modifier = Modifier,
) {
    // 轮播的同图底色铺到状态栏后面：颜色只来自当前页那张图，切页淡入。
    val banners = (state as? HomeUiState.Content)?.banners.orEmpty()
    val bannerPager = rememberPagerState(pageCount = { banners.size.coerceAtLeast(1) })
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        HomeBannerBackdrop(banners = banners, pager = bannerPager)
        CompositionLocalProvider(LocalHomeBannerPager provides bannerPager) {
            Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                when (state) {
                    is HomeUiState.Loading -> StarveilLoadingScreen()

                    is HomeUiState.Error -> Centered {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = state.message, color = starveilExtraColors.textMuted)
                            Button(onClick = actions.onRetry, modifier = Modifier.height(Spacing.xxl)) {
                                Text("重试")
                            }
                        }
                    }

                    is HomeUiState.Empty -> EmptyFeed(state = state, actions = actions)

                    is HomeUiState.Content -> HomeFeed(state = state, actions = actions)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeFeed(state: HomeUiState.Content, actions: HomeActions) {
    Column(modifier = Modifier.fillMaxSize()) {
        HomeTopBar(
            hotWord = state.searchHot.firstOrNull().orEmpty(),
            pushCount = state.pushCount,
            actions = actions,
            types = state.types,
            selectedTypeId = state.selectedTypeId,
        )
        // 分类不再顶在搜索栏下：放到轮播图下面，替换原来的快捷入口那一排
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = actions.onRefresh,
            modifier = Modifier.weight(1f),
        ) {
            HomeFeedList(state = state, actions = actions)
        }
    }
}

/**
 * 首页正文：按 `home_modules` 决定哪些区块出现。
 *
 * 运营位为空（或模块开关关掉）时回退到内置区块，保证老后台配置下页面不空。
 */
@Composable
private fun HomeFeedList(state: HomeUiState.Content, actions: HomeActions) {
    val banner = LocalAdPlacements.current.banner
    val listState = rememberLazyListState()
    val categoryIds = remember(state.types) { listOf(0) + state.types.map { it.id } }
    LaunchedEffect(state.selectedTypeId) { listState.scrollToItem(0) }
    if (state.isSwitching) {
        // 目标分类还在路上：上骨架，别把上一个分类的列表留在屏幕上骗人
        HomeFeedSkeleton()
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .categorySwipe(categoryIds, state.selectedTypeId, actions.onSelectType),
        ) {
            homeSections(state, actions, banner)
        }
    }
}

private fun Modifier.categorySwipe(
    categoryIds: List<Int>,
    selectedTypeId: Int,
    onSelect: (Int) -> Unit,
): Modifier = composed {
    val swipeDistancePx = LocalViewConfiguration.current.touchSlop * SWIPE_SLOP_MULTIPLIER
    pointerInput(categoryIds, selectedTypeId, swipeDistancePx) {
        var horizontalDragDistance = 0f
        detectHorizontalDragGestures(
            onHorizontalDrag = { change, dragAmount ->
                change.consume()
                horizontalDragDistance += dragAmount
            },
            onDragEnd = {
                val currentIndex = categoryIds.indexOf(selectedTypeId)
                if (currentIndex >= 0) {
                    val nextIndex = when {
                        horizontalDragDistance > swipeDistancePx -> currentIndex - 1
                        horizontalDragDistance < -swipeDistancePx -> currentIndex + 1
                        else -> currentIndex
                    }
                    categoryIds.getOrNull(nextIndex)?.let(onSelect)
                }
                horizontalDragDistance = 0f
            },
            onDragCancel = { horizontalDragDistance = 0f },
        )
    }
}

private const val SWIPE_SLOP_MULTIPLIER = 2.5f

/** 切分类时的骨架：轮播 + 两组「标题 + 三张竖卡」，形状与真实首页对齐。 */
@Composable
private fun HomeFeedSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.lg),
    ) {
        Spacer(Modifier.height(Spacing.md))
        SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(BANNER_RATIO),
            shape = AppShape.large,
        )
        repeat(SKELETON_ROWS) {
            Spacer(Modifier.height(Spacing.xl))
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(SKELETON_TITLE_WIDTH)
                    .height(Spacing.lg),
            )
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                repeat(SKELETON_COLUMNS) {
                    SkeletonBlock(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(POSTER_RATIO),
                    )
                }
            }
        }
    }
}

/** 骨架里标题条占的宽度比例：与「热度榜」那种两三个字的标题差不多长。 */
private const val SKELETON_TITLE_WIDTH = 0.32f

/** 骨架摆几组「标题 + 海报行」：两行刚好铺满一屏可见区域。 */
private const val SKELETON_ROWS = 2

/** 每行几张竖卡：与真实网格同列数。 */
private const val SKELETON_COLUMNS = 3
/**
 * 正文按后台 `home_modules` 的**顺序**铺：写了什么就按什么顺序出，没写的模块不出现。
 *
 * 编辑精选也是模块之一（`featured`），后台没开就不出这一块。
 */
private fun androidx.compose.foundation.lazy.LazyListScope.homeSections(
    state: HomeUiState.Content,
    actions: HomeActions,
    banner: AdPlacement,
) {
    state.modules.forEach { module -> homeModuleSection(module, state, actions) }
    item { Spacer(Modifier.height(Spacing.xl)) }
    // 横幅固定压在最后一条：无素材时整条 item 都不生成，不会留下空位或占位灰块。
    if (banner.drawable) {
        item { HomeBannerAd(creative = banner) }
    }
}

/** 一个模块一段；顺序由调用方按 `home_modules` 决定，这里只管怎么画。 */
private fun androidx.compose.foundation.lazy.LazyListScope.homeModuleSection(
    module: HomeModule,
    state: HomeUiState.Content,
    actions: HomeActions,
) {
    when (module) {
        HomeModule.BANNER -> if (state.banners.isNotEmpty()) {
            item { BannerHero(banners = state.banners, onClick = actions.onBannerClick) }
            item { Spacer(Modifier.height(Spacing.md)) }
        }

        HomeModule.FEATURED -> if (state.featured.items.isNotEmpty()) {
            item {
                SectionTitle(
                    title = state.featured.title.ifBlank { "编辑精选" },
                    action = "更多",
                    onAction = { actions.onShortcut(HomeShortcut.ALL) },
                )
            }
            item { EditorialRow(items = state.featured.items, onVodClick = actions.onVodClick) }
        }

        HomeModule.LINKS -> if (state.links.isNotEmpty()) {
            item { Spacer(Modifier.height(Spacing.md)) }
            item { HomeLinksRow(links = state.links, onClick = actions.onLinkClick) }
        }

        HomeModule.CONTINUE -> if (state.continueWatching.isNotEmpty()) {
            item { ContinueWatchingSection(state.continueWatching, actions.onVodClick) }
        }

        HomeModule.BLOCKS -> items(state.blocks, key = { it.id }) { block ->
            HomeBlockSection(block = block, onVodClick = actions.onVodClick)
        }

        HomeModule.TOPIC -> if (state.topics.isNotEmpty()) {
            item { SectionTitle(title = "专题") }
            item { TopicRow(items = state.topics, onClick = actions.onTopicClick) }
        }

        HomeModule.LIVE -> if (state.lives.isNotEmpty()) {
            item { SectionTitle(title = "直播") }
            item { LiveRow(items = state.lives, onClick = actions.onLiveClick) }
        }

        HomeModule.HOT -> hotSection(state, actions)

        HomeModule.GUESS -> guessSection(state, actions)
    }
}

/** 热度榜：优先用后台配置；为空（老后端）时沿用「列表从第四条起」的切片。 */
private fun androidx.compose.foundation.lazy.LazyListScope.hotSection(
    state: HomeUiState.Content,
    actions: HomeActions,
) {
    val hotItems = state.hot.items.ifEmpty { state.items.drop(3) }
    item { SectionTitle(title = state.hot.title.ifBlank { "热度榜" }) }
    item { RankRow(items = hotItems, onVodClick = actions.onVodClick) }
}

/** 猜你喜欢：同上，回退切片从第七条起。 */
private fun androidx.compose.foundation.lazy.LazyListScope.guessSection(
    state: HomeUiState.Content,
    actions: HomeActions,
) {
    // 按列数取整：末行不留空位。回退用的是"主列表去掉前 6 部"（20-6=14），
    // 三列时 14 除不尽，最后一行就空一个——14 这个数不是配置，是 20 减 6 的差。
    val fallback = state.items.drop(GUESS_SKIP).ifEmpty { state.items }
    val guessItems = gridFilled(state.guess.items.ifEmpty { fallback }, state.columns)
    item { SectionTitle(title = state.guess.title.ifBlank { "猜你喜欢" }) }
    item {
        PosterRows(
            items = guessItems,
            columns = state.columns,
            onVodClick = actions.onVodClick,
        )
    }
}

@Composable
private fun EmptyFeed(state: HomeUiState.Empty, actions: HomeActions) {
    Column(modifier = Modifier.fillMaxSize()) {
        HomeTopBar(
            hotWord = state.searchHot.firstOrNull().orEmpty(),
            actions = actions,
            types = state.types,
            selectedTypeId = state.selectedTypeId,
        )
        // 空分类也不整页黑：先轮播占位，最后一句提示。
        Spacer(Modifier.height(Spacing.md))
        BannerPlaceholder()
        Spacer(Modifier.height(Spacing.xl))
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodySmall,
            color = starveilExtraColors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.xl))
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/**
 * 底部横幅广告：素材与落地页都由启动配置提供，feature 侧不拼 Intent、不弹 Toast。
 *
 * 点击交给 [LocalAdClick]（`:app` 注入）；落地页为空时不发跳转，免得打开一张空白页。
 * 调用方已按 [AdPlacement.drawable] 过滤，这里不画空态。
 */
@Composable
private fun HomeBannerAd(creative: AdPlacement) {
    val openLanding = LocalAdClick.current
    Column(modifier = Modifier.fillMaxWidth()) {
        // 上下都要留白：之前只有底部一条 Spacer，贴着上一块内容会读成"填满了"。
        Spacer(Modifier.height(Spacing.lg))
        AdCreativeCard(
            creative = creative,
            onClick = { if (creative.clickUrl.isNotBlank()) openLanding(creative.clickUrl) },
            modifier = Modifier.padding(horizontal = Spacing.pageHorizontal),
            aspectRatio = AD_WIDE_RATIO,
        )
        Spacer(Modifier.height(Spacing.xl))
    }
}

/** 猜你喜欢回退切片从第几条起：前 6 部通常已经出现在轮播或推荐条里。 */
private const val GUESS_SKIP = 6

/**
 * 截到整行：末行不足一整行时丢掉尾部，网格最后一行才不会空出格子。
 *
 * 不足一整行时原样返回——这时候"少两个"比"什么都不显示"更糟。
 */
private fun gridFilled(items: List<Vod>, columns: Int): List<Vod> {
    val cols = columns.coerceAtLeast(1)
    if (items.size < cols) return items
    return items.take(items.size - items.size % cols)
}
