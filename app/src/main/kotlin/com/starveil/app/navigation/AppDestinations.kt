package com.starveil.app.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.starveil.core.designsystem.LocalMemberEnabled
import com.starveil.core.designsystem.LocalLoginPrompt
import com.starveil.core.designsystem.LoginReason
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.starveil.app.AppContainer
import com.starveil.app.ads.AdSplashGate
import com.starveil.app.R
import com.starveil.core.common.AppResult
import com.starveil.core.model.Skins
import com.starveil.core.model.Vod
import com.starveil.core.model.RegisterMethod
import com.starveil.core.model.StartupConfig
import com.starveil.feature.user.RegisterMode
import com.starveil.core.model.UserCenterVariant
import com.starveil.feature.detail.detailRoute
import com.starveil.feature.home.HomeActions
import com.starveil.feature.home.HomeRoute
import com.starveil.feature.home.SearchRoute
import com.starveil.feature.home.SearchViewModel
import com.starveil.feature.home.HomeViewModel
import com.starveil.feature.home.FILTER_ROUTE
import com.starveil.feature.home.FilterRoute
import com.starveil.feature.home.FilterViewModel
import com.starveil.feature.live.LIVE_ROUTE
import com.starveil.feature.live.LivePlayerViewModel
import com.starveil.feature.live.LiveRoute
import com.starveil.feature.live.LiveViewModel
import com.starveil.feature.pay.ARG_EPISODE_NUMBER as PAY_ARG_EPISODE
import com.starveil.feature.pay.ARG_PAY_MODE as PAY_ARG_MODE
import com.starveil.feature.pay.ARG_SOURCE_INDEX as PAY_ARG_SOURCE
import com.starveil.feature.pay.ARG_SOURCE_SID as PAY_ARG_SID
import com.starveil.feature.pay.ARG_VOD_ID as PAY_ARG_VOD
import com.starveil.feature.pay.PAY_MODE_EPISODE
import com.starveil.feature.pay.PAY_ROUTE_PATTERN
import com.starveil.feature.pay.PayScreen
import com.starveil.feature.pay.PayViewModel
import com.starveil.feature.pay.payRoute
import com.starveil.feature.player.ARG_EPISODE_INDEX
import com.starveil.feature.player.ARG_SOURCE_INDEX
import com.starveil.feature.player.ARG_VOD_ID as PLAYER_ARG_VOD_ID
import com.starveil.feature.player.PLAYER_ROUTE_PATTERN
import com.starveil.feature.player.PlayerEvent
import com.starveil.feature.player.playerRoute
import com.starveil.feature.player.verticalPlayRoute
import com.starveil.feature.rank.RANK_ROUTE
import com.starveil.feature.rank.RankRoute
import com.starveil.feature.rank.RankViewModel
import com.starveil.feature.topic.ARG_TOPIC_ID
import com.starveil.feature.topic.TOPIC_DETAIL_ROUTE_PATTERN
import com.starveil.feature.topic.TOPIC_LIST_ROUTE
import com.starveil.feature.topic.TopicDetailScreen
import com.starveil.feature.topic.TopicDetailViewModel
import com.starveil.feature.topic.TopicRoute
import com.starveil.feature.topic.TopicViewModel
import com.starveil.feature.topic.topicDetailRoute
import com.starveil.feature.user.FavoriteScreen
import com.starveil.feature.user.FavoriteViewModel
import com.starveil.feature.user.LoginScreen
import com.starveil.feature.user.LoginViewModel
import com.starveil.feature.user.ProfileScreen
import com.starveil.feature.user.ProfileViewModel
import com.starveil.feature.user.RegisterScreen
import com.starveil.feature.user.RegisterViewModel
import com.starveil.feature.user.ResetPasswordScreen
import com.starveil.feature.user.ResetPasswordViewModel
import com.starveil.feature.user.UserCenterScreen
import com.starveil.feature.user.UserCenterViewModel
import com.starveil.feature.user.UserNavigation
import com.starveil.feature.user.WithdrawScreen
import com.starveil.feature.user.WithdrawViewModel
import com.starveil.feature.user.PointsViewModel
import com.starveil.feature.user.PointsScreen
import com.starveil.feature.user.FeedbackScreen
import com.starveil.feature.user.FeedbackViewModel
import com.starveil.feature.user.HistoryScreen
import com.starveil.feature.user.HistoryViewModel

/** 首页。历史版本首页卡片点进去是详情页，不是直接起播。 */
internal fun NavGraphBuilder.homeDestination(nav: NavHostController, container: AppContainer) {
    homeEntry(nav, container)
    searchEntry(nav, container)
    filterEntry(nav, container)
}

private fun NavGraphBuilder.homeEntry(nav: NavHostController, container: AppContainer) {
    composable("home") {
        val context = LocalContext.current
        val memberEnabled = LocalMemberEnabled.current
        HomeRoute(
            viewModel = homeViewModel(container),
            actions = homeActions(nav, context, memberEnabled, container),
        )
    }
}

private fun NavGraphBuilder.searchEntry(nav: NavHostController, container: AppContainer) {
    // 搜索与其它页面共用整幅横滑，避免整页 alpha 把旧短剧内容混到目标页面。
    composable(
        route = "search",

        enterTransition = { searchEnter() },
        exitTransition = { searchExit() },
        popEnterTransition = { searchPopEnter() },
        popExitTransition = { searchPopExit() },
    ) {
        SearchRoute(
            viewModel = viewModel(
                factory = SearchViewModel.factory(
                    repository = container.vodRepository,
                    historyStore = container.searchHistoryStore,
                    rankRepository = container.rankRepository,
                ),
            ),
            onVodClick = { vod -> nav.openVod(container, vod) },
            onBack = { nav.popBackStack() },
        )
    }
}

private fun NavGraphBuilder.filterEntry(nav: NavHostController, container: AppContainer) {
    // 片库筛选页：首页顶部「筛选」入口进来。`initialTypeId` 先固定 0（全部）——
    // 首页目前没有把选中的分类 id 透出来，等 `HomeActions` 带上它再换这一行。
    composable(
        route = FILTER_ROUTE,
        // 与详情页同一套 push 转场：从首页推进来，返回时向右退出
        enterTransition = { pushEnter() },
        exitTransition = { pushExit() },
        popEnterTransition = { pushPopEnter() },
        popExitTransition = { pushPopExit() },
    ) {
        FilterRoute(
            viewModel = viewModel(
                factory = FilterViewModel.factory(container.vodRepository, initialTypeId = 0),
            ),
            onBack = { nav.popBackStack() },
            onVodClick = { vod -> nav.openVod(container, vod) },
            onSearch = { nav.navigate("search") },
        )
    }
}

/** 首页 ViewModel 的装配点：六个仓库的注入放这里取，`homeDestination` 才不至于超长。 */
@Composable
private fun homeViewModel(container: AppContainer): HomeViewModel = viewModel(
    factory = HomeViewModel.factory(
        repository = container.vodRepository,
        startupRepository = container.startupRepository,
        continueWatchingRepository = container.continueWatchingRepository,
        // 首页的专题/直播区块；不给就退回空实现，这两块整片不渲染
        topicRepository = container.topicRepository,
        liveRepository = container.liveRepository,
        onReady = AdSplashGate::markHomeReady,
    ),
)

/** 首页对外动作的装配点：页面只上报点击，跳哪一页在这里定。 */
private fun homeActions(
    nav: NavHostController,
    context: Context,
    memberEnabled: Boolean,
    container: AppContainer,
): HomeActions = HomeActions(
    onVodClick = { vod -> nav.openVod(container, vod) },
    onBannerClick = { banner -> nav.openHomeBanner(banner, context) },
    onLinkClick = { link -> nav.openHomeLink(link, context) },
    onSearch = { nav.navigate("search") },
    onFilter = { nav.navigate(FILTER_ROUTE) },
    onShortcut = { shortcut -> nav.openHomeShortcut(shortcut, memberEnabled, context) },
)

/**
 * 短剧进竖屏播放器，普通影片进详情页。
 *
 * 判定只认后台配的短剧分类 id（`shortplay_type_id` / `shortplay_types`），不看分类名：
 * 短剧内容挂在子分类下（古装仙侠、现代都市…），名字里没有"短剧"三个字，
 * 靠名字判会把它们全送进详情页的 16:9 播放器。
 */
private fun NavHostController.openVod(container: AppContainer, vod: Vod) {
    val shortplayIds = container.startupRepository.cached()?.shortplayTypeIds.orEmpty()
    val isShortPlay = vod.typeId in shortplayIds || vod.isShortPlay
    if (isShortPlay) {
        navigate(verticalPlayRoute(vod.id, SHORT_PLAY_FIRST_EPISODE))
    } else {
        navigate(detailRoute(vod.id))
    }
}

/** 从首页点进短剧从第 1 集起播；看过哪集由竖屏播放器自己按断点接。 */
private const val SHORT_PLAY_FIRST_EPISODE = 1

/** 播放 / 详情 / 支付：三者互相跳转，放在一起注册。 */
internal fun NavGraphBuilder.playerDestinations(
    nav: NavHostController,
    container: AppContainer,
    context: Context,
) {
    playerDestination(nav, container, context)
    verticalPlayDestination(nav, container)
    detailDestination(nav, container)
    payDestination(nav, container, context)
}

private fun NavGraphBuilder.playerDestination(
    nav: NavHostController,
    container: AppContainer,
    context: Context,
) {
    composable(
        route = PLAYER_ROUTE_PATTERN,
        arguments = listOf(
            navArgument(PLAYER_ARG_VOD_ID) { type = NavType.IntType },
            navArgument(ARG_SOURCE_INDEX) { type = NavType.IntType; defaultValue = 0 },
            navArgument(ARG_EPISODE_INDEX) { type = NavType.IntType; defaultValue = 0 },
        ),
    ) { entry ->
        val args = entry.arguments
        val memberEnabled = LocalMemberEnabled.current
        val fullscreenController = LocalFullscreenController.current
        // 独立播放页固定横屏：观看记录直接点进来也是横屏（这里之前没人请求方向，
        // 于是停在竖屏只露一条画面）。离开页面恢复竖屏。
        DisposableEffect(Unit) {
            context.applyPlayerFullscreen(true)
            onDispose { context.applyPlayerFullscreen(false) }
        }
        PlayerEntry(
            container = container,
            vodId = args?.getInt(PLAYER_ARG_VOD_ID) ?: 0,
            sourceIndex = args?.getInt(ARG_SOURCE_INDEX) ?: 0,
            episodeIndex = args?.getInt(ARG_EPISODE_INDEX) ?: 0,
            onEvent = { event -> nav.handlePlayerEvent(event, memberEnabled, context) },
            onPurchase = { vodId, episodeNumber, sourceIndex, sid ->
                nav.navigate(
                    payRoute(
                        mode = PAY_MODE_EPISODE,
                        vodId = vodId,
                        episodeNumber = episodeNumber,
                        sourceIndex = sourceIndex,
                        sid = sid,
                    ),
                )
            },
            onFullscreenChange = { fullscreen -> fullscreenController.set(fullscreen) },
        )
    }
}

private fun NavGraphBuilder.payDestination(
    nav: NavHostController,
    container: AppContainer,
    context: Context,
) {
    composable(
        route = PAY_ROUTE_PATTERN,
        arguments = listOf(
            navArgument(PAY_ARG_MODE) { type = NavType.StringType; defaultValue = "" },
            navArgument(PAY_ARG_VOD) { type = NavType.IntType; defaultValue = 0 },
            navArgument(PAY_ARG_EPISODE) { type = NavType.IntType; defaultValue = 0 },
            navArgument(PAY_ARG_SOURCE) { type = NavType.IntType; defaultValue = 0 },
            navArgument(PAY_ARG_SID) { type = NavType.IntType; defaultValue = 0 },
        ),
    ) { entry ->
        val args = entry.arguments
        // 会员关闭时会员页不可达；单集积分兑换（episode）必须保留
        if (!LocalMemberEnabled.current && args?.getString(PAY_ARG_MODE) != PAY_MODE_EPISODE) {
            LaunchedEffect(Unit) { nav.popBackStack() }
            return@composable
        }
        val payViewModel: PayViewModel = rememberPayViewModel(nav, container, args)
        // 从浏览器回跳时页面不会重建，靠 ON_RESUME 触发一次订单状态查询；
        // 页面常驻，顺带同步一次登录态，退出登录后 Hero 不会挂在旧账号上
        androidx.lifecycle.compose.LifecycleEventEffect(
            androidx.lifecycle.Lifecycle.Event.ON_RESUME,
            onEvent = {
                payViewModel.onReturnedFromPayment()
                payViewModel.syncSession()
            },
        )
        PayScreen(
            viewModel = payViewModel,
            onBack = { nav.popBackStack() },
            onOpenPayment = { url -> context.openPaymentUrl(url) },
        )
    }
}

/** 专题 / 直播 / 榜单。 */
internal fun NavGraphBuilder.catalogDestinations(nav: NavHostController, container: AppContainer) {
    topicDestinations(nav, container)
    shortPlayDestination(nav, container)
    liveDestination(nav, container)
    rankDestination(nav, container)
}

private fun NavGraphBuilder.topicDestinations(nav: NavHostController, container: AppContainer) {
    composable(TOPIC_LIST_ROUTE) {
        TopicRoute(
            viewModel = tabViewModel<TopicViewModel>(
                nav = nav,
                factory = TopicViewModel.factory(container.topicRepository),
            ),
            onTopicClick = { topicId -> nav.navigate(topicDetailRoute(topicId)) },
        )
    }
    composable(
        route = TOPIC_DETAIL_ROUTE_PATTERN,
        arguments = listOf(navArgument(ARG_TOPIC_ID) { type = NavType.StringType }),
    ) { entry ->
        val topicId = entry.arguments?.getString(ARG_TOPIC_ID).orEmpty()
        val detailViewModel: TopicDetailViewModel = viewModel(
            key = "topic-$topicId",
            factory = TopicDetailViewModel.factory(container.topicRepository, topicId),
        )
        val state by detailViewModel.state.collectAsStateWithLifecycle()
        TopicDetailScreen(
            state = state,
            onVodClick = { vodId -> nav.navigate(detailRoute(vodId)) },
            onRetry = detailViewModel::retry,
        )
    }
}

private fun NavGraphBuilder.liveDestination(nav: NavHostController, container: AppContainer) {
    composable(LIVE_ROUTE) {
        LiveRoute(
            // 直播列表是底栏根页面：切走再回来不重拉频道。
            // 播放已改成点开全屏播放器（自带实例），不再需要页面级播放器 ViewModel。
            listViewModel = tabViewModel<LiveViewModel>(
                nav = nav,
                factory = LiveViewModel.factory(container.liveRepository),
            ),
        )
    }
}

private fun NavGraphBuilder.rankDestination(nav: NavHostController, container: AppContainer) {
    composable(RANK_ROUTE) {
        RankRoute(
            viewModel = tabViewModel<RankViewModel>(
                nav = nav,
                factory = RankViewModel.factory(container.rankRepository),
            ),
            onVodClick = { vodId -> nav.navigate(detailRoute(vodId)) },
        )
    }
}


