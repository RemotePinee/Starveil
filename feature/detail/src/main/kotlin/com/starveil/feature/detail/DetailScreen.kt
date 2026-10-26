package com.starveil.feature.detail

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starveil.core.common.DetailSections
import com.starveil.core.designsystem.component.SectionHeader
import com.starveil.core.designsystem.LocalDetailSections
import com.starveil.core.designsystem.component.StarveilLoadingScreen
import com.starveil.core.designsystem.token.Spacing

/**
 * 详情页入口：收状态、弹一次性 Toast、拼回调。
 *
 * 详情曾被做成一个 1163 行的 Fragment，播放在同一个宿主里，
 * 简介、选集、收藏、推荐全挤在一起；这里只保留详情页本身的内容，
 * 起播交给 [onPlay]；是否跳独立播放页由 :app 决定。详情页自己不认识播放器，
 * 想内嵌就把 [heroSlot] 传进来，优酷式的「详情 + 半屏播放器」就是这条路径。
 *
 * 评论区是页面内的一块（[DetailCommentSection]），不新增路由：
 * 跳走会丢掉当前播放源与选集位置。
 */
@Composable
fun DetailRoute(
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    onPlay: (sourceIndex: Int, episodeIndex: Int) -> Unit,
    onRecommendClick: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    heroSlot: DetailHeroSlot? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val comments by viewModel.comments.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is DetailEvent.Toast -> showToast(context, event.message)
                is DetailEvent.CommentPosted -> showToast(context, COMMENT_POSTED)
            }
        }
    }
    DetailScreen(
        state = state,
        comments = comments,
        actions = DetailActions(
            onBack = onBack,
            onRetry = viewModel::retry,
            onRefresh = viewModel::refresh,
            onSourceSelect = viewModel::selectSource,
            onEpisodeSelect = viewModel::selectEpisode,
            onToggleBlurb = viewModel::toggleBlurb,
            onToggleEpisodeGrid = viewModel::toggleEpisodeGrid,
            onToggleFavorite = viewModel::toggleFavorite,
            onPlay = onPlay,
            onRecommendClick = onRecommendClick,
            onExpandComments = viewModel::toggleCommentsExpanded,
            onLoadMoreComments = viewModel::loadMoreComments,
            onRetryComments = viewModel::loadComments,
            onSubmitComment = viewModel::submitComment,
        ),
        modifier = modifier,
        heroSlot = heroSlot,
    )
}

/** 详情页回调集合，避免 [DetailContent] 参数越堆越多（detekt 上限 5 个）。 */
data class DetailActions(
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val onRefresh: () -> Unit,
    val onSourceSelect: (Int) -> Unit,
    val onEpisodeSelect: (Int) -> Unit,
    val onToggleBlurb: () -> Unit,
    val onToggleEpisodeGrid: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onPlay: (sourceIndex: Int, episodeIndex: Int) -> Unit,
    /** 点相关推荐卡片：上抛目标影片 id，跳转由 :app 的导航决定。 */
    val onRecommendClick: (Int) -> Unit,
    /** 「查看全部 N 条」/「收起」：同页展开，不跳路由。 */
    val onExpandComments: () -> Unit,
    /** 展开后底部的「加载更多」。 */
    val onLoadMoreComments: () -> Unit,
    /** 评论区首屏失败的重试。 */
    val onRetryComments: () -> Unit,
    /** 发送评论；空白内容由 ViewModel 丢弃。 */
    val onSubmitComment: (String) -> Unit,
)

/**
 * 纯 UI：只读 [DetailUiState]，所有动作经 [DetailActions] 上抛。
 *
 * 三态（Loading 加载态 / Error 可重试 / Content）只换正文区块，[DetailTopBar]
 * 空态与错误态复用同一个居中占位，保留可重试入口；
 * 旧页在没有播放源时是整片留白，用户不知道发生了什么。
 */
@Composable
fun DetailScreen(
    state: DetailUiState,
    comments: CommentSectionState,
    actions: DetailActions,
    modifier: Modifier = Modifier,
    heroSlot: DetailHeroSlot? = null,
) {
    val listState = rememberLazyListState()
    val heroHeight = remember { mutableIntStateOf(0) }
    // 插槽自报铺满整屏时详情页的顶栏要让位，否则会盖在播放器控制层上
    var heroFullscreen by rememberSaveable { mutableStateOf(false) }
    val chromeVisible = heroSlot == null && (!heroFullscreen || state !is DetailUiState.Content)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            when (state) {
                is DetailUiState.Loading -> StarveilLoadingScreen()

                is DetailUiState.Empty -> DetailStateMessage(
                    title = "暂无播放资源",
                    detail = state.message,
                    actionLabel = "刷新",
                    onAction = actions.onRefresh,
                    modifier = Modifier.align(Alignment.Center),
                )

                is DetailUiState.Error -> DetailStateMessage(
                    title = "详情加载失败",
                    detail = state.message,
                    actionLabel = "重试",
                    onAction = actions.onRetry,
                    modifier = Modifier.align(Alignment.Center),
                )

                is DetailUiState.Content -> DetailContent(
                    data = DetailContentData(
                        state = state,
                        comments = comments,
                        withHero = heroSlot == null,
                        fullscreen = heroFullscreen,
                    ),
                    actions = actions,
                    hero = DetailHeroArea(
                        slot = heroSlot,
                        onHeight = { heroHeight.intValue = it },
                        onFullscreenChange = { heroFullscreen = it },
                    ),
                    listState = listState,
                )
            }
            // 顶栏始终在，标题只有拿到影片后才有；Loading/Error 时 title 为 null，固定实底。
            if (chromeVisible) {
                val content = state as? DetailUiState.Content
                DetailTopBar(
                    title = content?.vod?.name,
                    listState = listState,
                    heroHeightPx = heroHeight.intValue,
                    onBack = actions.onBack,
                )
            }
        }
    }
}

/**
 * 头图区的装配参数：插槽（null 表示走 [DetailHero] 海报）、高度回传、全屏回传。
 *
 * 收成一个对象是为了 [DetailContent] 的参数不超限（AGENTS.md 第 2 节）。
 */
private class DetailHeroArea(
    val slot: DetailHeroSlot?,
    val onHeight: (Int) -> Unit,
    val onFullscreenChange: (Boolean) -> Unit,
)

private data class DetailContentData(
    val state: DetailUiState.Content,
    val comments: CommentSectionState,
    val withHero: Boolean,
    val fullscreen: Boolean = false,
)

/** 列表渲染参数：Tab 状态 + 详情页三块开关。收成对象只为压住 [videoItems] 的参数个数。 */
private data class DetailListConfig(
    val tab: DetailTabState,
    val sections: DetailSections,
)

@Composable
private fun DetailContent(
    data: DetailContentData,
    actions: DetailActions,
    hero: DetailHeroArea,
    listState: LazyListState,
) {
    val state = data.state
    // 视频 / 讨论 两个 Tab 的内容不同，选中项存下标（rememberSaveable 不认枚举）
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    // 后台「设置 → 详情页」的三个开关：关掉评论时讨论 Tab 不该存在，
    // 选中项必须退回视频，否则会停在一个不存在的 Tab 上（列表空白）
    val sections = LocalDetailSections.current
    LaunchedEffect(sections.comment) {
        if (!sections.comment && tabIndex != DetailTab.Video.ordinal) {
            tabIndex = DetailTab.Video.ordinal
        }
    }
    // 全屏就只留播放器：与 PlayerScreen / InlineDetailPlayer 同一口径，不再按方向二次判断
    if (data.fullscreen && hero.slot != null) {
        // 全屏：只留播放器铺满整屏，详情正文与头图都不参与布局
        Box(modifier = Modifier.fillMaxSize()) {
            hero.slot.invoke(
                HeroSlotScope(content = state, onFullscreenChange = hero.onFullscreenChange),
            )
        }
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        if (hero.slot != null) {
            DetailHeroStage(state = state, hero = hero)
        }
        val tab = DetailTabState(
            selected = DetailTab.entries[tabIndex],
            onSelect = { tabIndex = it.ordinal },
        )
        val config = DetailListConfig(tab = tab, sections = sections)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = Spacing.lg),
        ) {
            when (tab.selected) {
                DetailTab.Video -> videoItems(data, actions, hero.onHeight, config)
                DetailTab.Comments -> discussionItems(data, actions, hero.onHeight, config)
            }
        }
    }
}

/**
 * 插槽态的头图区：钉在列表上方，不参与滚动。
 *
 * 内嵌播放器一旦起播就不能被 [LazyColumn] 回收（滑出视口即销毁），
 * 所以这里不把它放进列表项，而是让列表从它下面开始滚。插槽自带尺寸：
 * 半屏时是 16:9，全屏时铺满整屏（父 [Box] 只限宽，高度交给插槽）。
 */
@Composable
private fun DetailHeroStage(state: DetailUiState.Content, hero: DetailHeroArea) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(HERO_ASPECT)
                .onSizeChanged { hero.onHeight(it.height) },
        ) {
            DetailHero(vod = state.vod, onHeight = {})
        }
        hero.slot?.invoke(HeroSlotScope(content = state, onFullscreenChange = hero.onFullscreenChange))
    }
}

/**
 * 视频 Tab：头图 → 标题区 → Tab → 简介 → 播放源 → 选集 → 试看 → 推荐。
 *
 * 评论不在这里：它属于「讨论」Tab（用户要求评论从选集下面挪走）。推荐只排一次，
 * 之前上下各挂一份，滑到底会看到两段重复。
 */
private fun LazyListScope.videoItems(
    data: DetailContentData,
    actions: DetailActions,
    onHeroHeight: (Int) -> Unit,
    config: DetailListConfig,
) {
    val state = data.state
    videoHeaderItems(data, actions, onHeroHeight, config)
    tabItem("blurb") { DetailBlurb(state = state, onToggle = actions.onToggleBlurb) }
    tabItem("sources") {
        DetailSourceTabs(
            sources = state.vod.playSources,
            selectedIndex = state.sourceIndex,
            onSelect = actions.onSourceSelect,
        )
    }
    tabItem("episodes") {
        DetailEpisodeParts(
            episodes = state.vod.playSources.getOrNull(state.sourceIndex)?.episodes
                ?: state.visibleEpisodes,
            selectedIndex = state.episodeIndex,
            onSelect = { index -> selectEpisode(actions, state, index) },
        )
    }
    tabItem("trysee") { DetailTrySeeBar(trySee = state.trySee) }
    // 关掉「推荐位」时连 item 都不注册：LazyColumn 里根本不存在这一条，
    // 不是渲染一个空 Column（那会留下一个不可见的间距）
    if (config.sections.recommend) {
        tabItem("recommend") {
            DetailRecommendations(
                vods = state.recommendations,
                loading = state.recommendationsLoading,
                onSelect = actions.onRecommendClick,
            )
        }
    }
}

/**
 * 头图 / 标题摘要 / Tab 三块。
 *
 * 单独成函数是因为 [videoItems] 会顶到 detekt 的 40 行上限；
 * 这条边界也是天然的——上面三块受「详情页」开关影响，下面的播放区不受。
 */
private fun LazyListScope.videoHeaderItems(
    data: DetailContentData,
    actions: DetailActions,
    onHeroHeight: (Int) -> Unit,
    config: DetailListConfig,
) {
    val state = data.state
    if (data.withHero) {
        item(key = "hero") { DetailHero(vod = state.vod, onHeight = onHeroHeight) }
    }
    item(key = "summary") {
        DetailSummary(
            state = state,
            favorite = state.favorite,
            onToggleFavorite = actions.onToggleFavorite,
            showScore = config.sections.score,
        )
    }
    item(key = "tabs") {
        DetailTabs(
            selected = config.tab.selected,
            commentCount = data.comments.total,
            onSelect = config.tab.onSelect,
            showComments = config.sections.comment,
        )
    }
}

/** Tab 下方区块：key 随 Tab 变，[animateItem] 让旧内容淡出、新内容淡入。 */
private fun LazyListScope.tabItem(key: String, content: @Composable () -> Unit) {
    item(key = key, contentType = key) {
        Box(modifier = Modifier.animateItem()) { content() }
    }
}

/**
 * 讨论 Tab：上方的头图 / 片名 / 评分 / 收藏照旧，只把标签下面换成评论区。
 *
 * 旧分支只挂了 Tab 行，切过去时标题区整块消失；现在和视频 Tab 共用同一批 key，那几块原地不动。
 */
private fun LazyListScope.discussionItems(
    data: DetailContentData,
    actions: DetailActions,
    onHeroHeight: (Int) -> Unit,
    config: DetailListConfig,
) {
    videoHeaderItems(data, actions, onHeroHeight, config)
    tabItem("comments") { DetailCommentSection(state = data.comments, actions = actions) }
}

private const val COMMENT_POSTED = "评论已发布"

private fun showToast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

/**
 * 点集即起播：宫格下标是"当前源里的集下标"，先改高亮，再按当前源起播这一集。
 *
 * 独立「播放」按钮撤掉之后，这里是详情页唯一的显式起播入口；内嵌播放器由 :app
 * 接住 [DetailActions.onPlay]，独立播放页则换路由，高亮与播放不能只走一半。
 */
private fun selectEpisode(actions: DetailActions, state: DetailUiState.Content, index: Int) {
    actions.onEpisodeSelect(index)
    actions.onPlay(state.sourceIndex, index)
}
