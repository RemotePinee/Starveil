package com.starveil.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.composable
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.currentBackStackEntryAsState
import com.starveil.app.AppContainer
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.ShortPlay
import com.starveil.core.model.ShortPlayType
import com.starveil.feature.player.FIRST_EPISODE_NUMBER
import com.starveil.feature.shortplay.SHORT_PLAY_ROUTE
import com.starveil.feature.shortplay.SHORT_PLAY_CATEGORY_ARG_TYPE_ID
import com.starveil.feature.shortplay.SHORT_PLAY_CATEGORY_ROUTE_PATTERN
import com.starveil.feature.shortplay.ShortPlayNavigation
import com.starveil.feature.user.UserNavigation
import com.starveil.feature.player.VerticalPlayOptions
import com.starveil.feature.player.rememberShortPlayFeedPlayback
import com.starveil.feature.player.verticalPlayRoute
import com.starveil.feature.shortplay.ShortPlayRoute
import com.starveil.feature.shortplay.ShortPlayPresentation
import com.starveil.feature.shortplay.ShortPlayViewModel
import com.starveil.feature.shortplay.ShortPlayCategoryRoute
import com.starveil.feature.shortplay.ShortPlayCategoryViewModel
import com.starveil.feature.shortplay.shortPlayCategoryRoute

/** 推荐与直属全集共享 origin feed 的同剧 VM；外部入口仍由自己的导航 entry 持有。 */
internal fun NavGraphBuilder.shortPlayDestination(
    nav: NavHostController,
    container: AppContainer,
) {
    composable(SHORT_PLAY_ROUTE) { entry -> ShortPlayEntry(container, nav, entry) }
    composable(
        // 分类页是独立一页：顶部分类条 + 卡片网格，取代原来的剧场页侧边抽屉。
        route = SHORT_PLAY_CATEGORY_ROUTE_PATTERN,
        arguments = listOf(
            navArgument(SHORT_PLAY_CATEGORY_ARG_TYPE_ID) {
                type = NavType.IntType
                defaultValue = 0
            },
        ),
        // 与详情页同一套 push 转场：从剧场页推进来，返回时向右退出
        enterTransition = { pushEnter() },
        exitTransition = { pushExit() },
        popEnterTransition = { pushPopEnter() },
        popExitTransition = { pushPopExit() },
    ) { entry ->
        val initialTypeId = entry.arguments?.getInt(SHORT_PLAY_CATEGORY_ARG_TYPE_ID) ?: 0
        ShortPlayCategoryEntry(container = container, nav = nav, initialTypeId = initialTypeId)
    }
}

/** 短剧分类页：分类条 + 卡片网格。点卡片进竖屏播放器，返回回到剧场页。 */
@Composable
private fun ShortPlayCategoryEntry(
    container: AppContainer,
    nav: NavHostController,
    initialTypeId: Int,
) {
    // 分类条的数据来自启动配置。cached() 在 startup 还没回来时是空列表，
    // 那一刻一级分类整行塌陷、二级分类上移；值到了又出现把它挤回去——
    // 用户看到的就是"一级分类慢一步、把下面的挤过去"。留一份最近的非空值兜住中间那几帧。
    val rememberedParents = remember { mutableStateOf(emptyList<ShortPlayType>()) }
    val liveParents = container.startupRepository.cached()?.shortplayCategories.orEmpty()
    if (liveParents.isNotEmpty()) rememberedParents.value = liveParents
    val parents = liveParents.ifEmpty { rememberedParents.value }
    val categoryViewModel: ShortPlayCategoryViewModel = viewModel(
        factory = ShortPlayCategoryViewModel.factory(
            repository = container.shortPlayRepository,
            // 与剧场页同源：后台下发的两级分类（mac_type.type_pid），客户端不改语义、不改数量
            parents = parents,
            initialTypeId = initialTypeId,
        ),
    )
    ShortPlayCategoryRoute(
        viewModel = categoryViewModel,
        onBack = { nav.popBackStack() },
        onPlay = { play -> nav.navigate(verticalPlayRoute(play.id, FIRST_EPISODE_NUMBER)) },
    )
}

@Composable
private fun ShortPlayEntry(container: AppContainer, nav: NavHostController, origin: NavBackStackEntry) {
    val feed: ShortPlayViewModel = viewModel(
        viewModelStoreOwner = origin,
        factory = ShortPlayViewModel.factory(
            repository = container.shortPlayRepository,
            // 分类由后台下发；老后端为空时仓库走兜底分类。
            types = container.startupRepository.cached()?.shortplayTypes.orEmpty(),
            // 搜索专用的父分类：0 = 后台没配，ViewModel 退回逐个子分类查。
            // 进页的初始分类不再由它决定：顶部 tab 第一个优先（见 defaultShortPlayTypeId）。
            searchTypeId = container.startupRepository.cached()?.shortplaySearchTypeId ?: 0,
            // 顶部横滑条单独配的一小组分类；空则由页面退回用上面的 types。
            tabs = container.startupRepository.cached()?.shortplayTabs.orEmpty(),
        ),
    )
    val store = remember(origin) { origin.shortPlayPresentationStore() }
    val session by store.state.collectAsStateWithLifecycle()
    val frame = LocalShortPlayPresentation.current
    val expansion = if (frame.store === store) frame.expansion else store.expansion.value
    val currentEntry by nav.currentBackStackEntryAsState()
    val inlineMode = remember(origin, store, feed, nav) {
        ShortPlayInlineMode(store, feed::pinPlayback) { nav.currentBackStackEntry?.id == origin.id }
    }
    // 先注册应用级返回，播放器内层后注册的选集/推荐 sheet 优先处理。
    BackHandler(enabled = session.expanded && currentEntry?.id == origin.id) {
        if (inlineMode.isCurrent()) store.close()
    }
    val contentPadding = LocalAppContentPadding.current
    val isOriginCurrent = currentEntry?.id == origin.id
    val stablePadding = rememberStableWhileCurrent(contentPadding, isOriginCurrent)
    // 宿主扣掉的 dock 占位由短剧页自己铺回来：切 tab 那一帧的高度必须和稳态一致。
    val bleed = rememberStableWhileCurrent(LocalHostBottomInset.current, isOriginCurrent)
    // 整个短剧 feed 一台播放器（一个 ViewModel/一屏一台正是解码器只增不减的根源），
    // 视频面也只在 pager 下面创建一次，滑页只换媒体项。
    val feedPlayback = rememberShortPlayFeedPlayback()
    CompositionLocalProvider(LocalAppContentPadding provides stablePadding) {
        ShortPlayRoute(
            viewModel = feed,
            // 退出转场期间不改测量高度：一改就会把贴底入口条推出可视区，
            // 看起来就是"那栏先消失、页面随后才滑走"。底槽留白改由前景占位保持。
            modifier = Modifier.fillMaxSize().bleedBottom(bleed),
            presentation = ShortPlayPresentation(
                seriesExpanded = session.expanded,
                interactionLocked = session.expanded,
                expansion = expansion,
            ),
            navigation = ShortPlayNavigation(
                onSearch = { nav.navigate("search") },
                onHistory = { nav.navigate(UserNavigation.HISTORY_ROUTE) },
                onFavorites = { nav.navigate(UserNavigation.FAVORITE_ROUTE) },
                onFeedback = { nav.navigate(UserNavigation.FEEDBACK_ROUTE) },
                // 顶部菜单：进入独立分类页，落点就是当前分类
                onCategories = { nav.navigate(shortPlayCategoryRoute(feed.state.value.selectedTypeId)) },
            ),
            // 不按 play.id 包 key：这一屏的组合身份已由 VerticalPager 的 page key 决定，
            // 再套一层 key 只会让换剧时整屏（背景 / 入口条 / 选集 pager）一起重建；
            // 页面内的换剧本来就是 load(vodId) 原地换数据，不需要新的组合身份。
            playerContent = { play, active, hostsVideo ->
                VerticalPlayEntry(
                    container, nav,
                    origin.shortPlayPreviewRequest(play, active, nav, inlineMode),
                    feedPlayback = feedPlayback,
                    hostsVideo = hostsVideo,
                )
            },
            // 广告整屏盖住播放面时停掉内容解码（不释放），回到正片由那一页重新 prepare。
            onCoveredChange = { covered -> if (covered) feedPlayback.stopForCover() },
        )
    }
}

@Composable
private fun NavBackStackEntry.shortPlayPreviewRequest(
    play: ShortPlay,
    active: Boolean,
    nav: NavHostController,
    inlineMode: ShortPlayInlineMode,
): VerticalPlayRequest {
    val store = inlineMode.store
    val session by store.state.collectAsStateWithLifecycle()
    val frame = LocalShortPlayPresentation.current
    val expanded = session.expanded && session.vodId == play.id
    val expansion = if (frame.store === store) frame.expansion else store.expansion.value
    return VerticalPlayRequest(
        vodId = play.id,
        episodeNumber = FIRST_EPISODE_NUMBER,
        origin = this,
        inlineMode = inlineMode,
        initialMetadata = play,
        onBack = { if (inlineMode.isCurrent()) store.close() },
        onUpgrade = { access, episode -> nav.navigate(payRouteFor(access, play.id, episode)) },
        options = VerticalPlayOptions(
            preview = !expanded,
            active = active,
            seriesExpansion = if (expanded) expansion else 0f,
            transitioning = expanded && session.transitioning,
        ),
    )
}

/**
 * 路由一换宿主的占位就跟着变，而旧页面还在组合里往外滑：当场改高度会先跳一下。
 * 所以只在"本页是当前目的地"时接受新值，转场期间保持上一帧的。
 */
@Composable
private fun <T> rememberStableWhileCurrent(value: T, isCurrent: Boolean): T {
    var retained by remember { mutableStateOf(value) }
    SideEffect { if (isCurrent) retained = value }
    return if (isCurrent) value else retained
}

/**
 * 向下多铺 [inset]，把宿主为 dock 扣掉的那块补回来。
 *
 * 越界的一段正好落在 dock 底下被它盖住，所以视觉与"整页铺到屏幕底"一致；
 * 关键是测量高度不再随路由变化，贴底那组（标题 / 右栏 / 观看完整短剧条）不会先往上跳。
 */
private fun Modifier.bleedBottom(inset: Dp): Modifier = if (inset <= Spacing.none) {
    this
} else {
    layout { measurable, constraints ->
        val maxHeight = if (constraints.hasBoundedHeight) {
            constraints.maxHeight + inset.roundToPx()
        } else {
            constraints.maxHeight
        }
        val placeable = measurable.measure(
            Constraints(constraints.minWidth, constraints.maxWidth, 0, maxHeight),
        )
        // 只报告宿主给的尺寸：多出来那段只是画到 dock 底下，声明溢出会让居中摆放的宿主
        // 把整页往上挪半格（实测正好半个底槽，入口条与 dock 之间露出一条视频）。
        val reported = if (constraints.hasBoundedHeight) constraints.maxHeight else placeable.height
        layout(placeable.width, reported) { placeable.place(0, 0) }
    }
}
