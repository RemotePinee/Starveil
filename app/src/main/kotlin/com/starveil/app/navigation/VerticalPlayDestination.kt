package com.starveil.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.starveil.app.AppContainer
import com.starveil.app.ads.RewardRequestContext
import com.starveil.core.common.AppResult
import com.starveil.core.model.TrySeeAccess
import com.starveil.core.model.TrySeeGate
import com.starveil.feature.pay.PAY_MODE_EPISODE
import com.starveil.feature.pay.payRoute
import com.starveil.feature.player.ARG_EPISODE_NUMBER
import com.starveil.feature.player.ARG_VOD_ID
import com.starveil.feature.player.FIRST_EPISODE_NUMBER
import com.starveil.feature.player.ARG_VERTICAL_RESUME_MS
import com.starveil.feature.player.VerticalPlayOptions
import com.starveil.feature.player.VERTICAL_PLAY_ROUTE_PATTERN
import com.starveil.feature.player.VERTICAL_PLAY_SOURCE_INDEX
import com.starveil.feature.player.VerticalPlayRoute
import com.starveil.feature.player.VerticalPlayViewModel
import com.starveil.feature.player.VerticalPlayUiState
import com.starveil.feature.player.verticalPlayEpisode
import com.starveil.feature.player.verticalPlayRoute
import com.starveil.feature.player.VERTICAL_ACTION_COMMENT
import com.starveil.core.common.ad.AdSlot
import com.starveil.core.designsystem.component.LocalRewardAd
import com.starveil.feature.player.UnlockOptions
import com.starveil.feature.player.unlockOptions

/** 短剧全集目的地：按当前集重新查询权限，返回仍回到推荐信息流。 */
internal fun NavGraphBuilder.verticalPlayDestination(
    nav: NavHostController,
    container: AppContainer,
) {
    composable(
        route = VERTICAL_PLAY_ROUTE_PATTERN,
        arguments = listOf(
            navArgument(ARG_VOD_ID) { type = NavType.IntType },
            navArgument(ARG_EPISODE_NUMBER) {
                type = NavType.IntType
                defaultValue = FIRST_EPISODE_NUMBER
            },
            navArgument(ARG_VERTICAL_RESUME_MS) {
                type = NavType.LongType
                defaultValue = 0L
            },
        ),
    ) { entry ->
        val args = entry.arguments
        val vodId = args?.getInt(ARG_VOD_ID) ?: 0
        val lease = remember(entry) { shortPlayHandoffLease(entry, nav.previousBackStackEntry, vodId) }
        val handoff = lease.handoff
        val currentEntry by nav.currentBackStackEntryAsState()
        val request = VerticalPlayRequest(
            vodId = vodId,
            episodeNumber = verticalPlayEpisode(args?.getInt(ARG_EPISODE_NUMBER)),
            onBack = {
                lease.finish()
                nav.popBackStack()
            },
            onUpgrade = { access, episodeNumber -> nav.navigate(payRouteFor(access, vodId, episodeNumber)) },
            resumeMs = args?.getLong(ARG_VERTICAL_RESUME_MS)?.coerceAtLeast(0L) ?: 0L,
        )
        // 比导航宿主更近；播放器内部选集/推荐面板的 BackHandler 仍优先关闭面板。
        BackHandler(enabled = handoff != null && currentEntry?.id == entry.id) { request.onBack() }
        VerticalPlayEntry(container = container, nav = nav, request = request, handoff = handoff)
    }
}

/** 试看到点的出口：VIP 走会员下单页，单集付费带上网址参数，与横屏播放页同一条映射。 */
internal fun payRouteFor(access: TrySeeAccess, vodId: Int, episodeNumber: Int): String =
    if (access == TrySeeAccess.NEED_VIP) {
        payRoute()
    } else {
        payRoute(
            mode = PAY_MODE_EPISODE,
            vodId = vodId,
            episodeNumber = episodeNumber,
            sourceIndex = VERTICAL_PLAY_SOURCE_INDEX,
        )
    }

/** 路由参数收成一个对象：`VerticalPlayEntry` 的参数就不会随交互增加而变长。 */
internal data class VerticalPlayRequest(
    val vodId: Int,
    val episodeNumber: Int,
    val onBack: () -> Unit,
    val onUpgrade: (TrySeeAccess, Int) -> Unit,
    val resumeMs: Long = 0L,
    val options: VerticalPlayOptions = VerticalPlayOptions(),
    val origin: NavBackStackEntry? = null,
    val inlineMode: ShortPlayInlineMode? = null,
    val initialMetadata: com.starveil.core.model.ShortPlay? = null,
)

@Composable
internal fun VerticalPlayEntry(
    container: AppContainer,
    nav: NavHostController,
    request: VerticalPlayRequest,
    modifier: Modifier = Modifier,
    handoff: ShortPlayHandoff? = null,
) {
    val showRewardAd = LocalRewardAd.current
    var gateRefresh by remember { mutableIntStateOf(0) }
    // 评论抽屉是目的地内的本地弹层：打开它不导航、不重建播放器，视频照常播。
    var showComments by rememberSaveable(request.vodId) { mutableStateOf(false) }
    val viewModel = rememberVerticalPlayViewModel(container, request, handoff)
    // 旧 observer 只归还跨 destination 的 handoff，绝不能结束原地 inline session。
    if (request.inlineMode == null) request.origin?.let { ObserveShortPlayPreview(it, viewModel, nav) }
    val playbackToken = handoff?.token ?: viewModel.defaultPlaybackToken
    val ownsPlayback = viewModel.ownsPlayback(playbackToken)
    val isActive = request.options.active && ownsPlayback
    val state by viewModel.state.collectAsStateWithLifecycle()
    val interactions = rememberShortPlayInteractions(
        container, request.vodId, state.title, active = isActive,
    )
    val recommendations = rememberShortPlayRecommendations(
        container, request.vodId, state.typeId, enabled = true,
    )
    val gateRequest = request.accessRequest(state, gateRefresh, isActive)
    val gate = rememberShortPlayGate(container, viewModel, gateRequest, playbackToken)
    val dockOffset = LocalAppContentPadding.current.calculateBottomPadding()
    val options = request.contentOptions(
        interactions = interactions,
        recommendations = recommendations,
        nav = nav,
        onComment = { showComments = true },
    ).copy(
        active = isActive && gate is AppResult.Success,
        accessPending = isActive && gate == null,
        seriesDockOffset = dockOffset,
        onOpenSeries = { episode, position ->
            request.openSeries(viewModel, playbackToken, episode, position)
        },
    )
    val contentPadding = PaddingValues(bottom = dockOffset)
    Box(modifier.fillMaxSize()) {
        VerticalPlayRoute(
            viewModel = viewModel,
            onBack = request.onBack,
            onUpgrade = request.onUpgrade,
            modifier = Modifier.padding(contentPadding).consumeWindowInsets(contentPadding),
            unlock = container.shortPlayUnlockOptions(gate),
            playbackToken = playbackToken,
            options = options,
            onWatchAd = {
                RewardRequestContext.begin(
                    container.sessionSnapshot()?.userId, request.vodId, gateRequest.episodeNumber,
                )
                showRewardAd {
                    RewardRequestContext.clear()
                    gateRefresh += 1
                }
            },
        )
        if (shouldShowShortPlayNotice(state, isActive)) {
            ShortPlayGateNotice(gate) { gateRefresh += 1 }
        }
        // 半屏评论抽屉压在播放器之上；关闭它只是把这个分支从组合里拿掉，播放器不受影响。
        if (showComments) {
            ShortPlayCommentSheet(
                container = container,
                vodId = request.vodId,
                onDismiss = { showComments = false },
            )
        }
    }
}

@Composable
private fun rememberVerticalPlayViewModel(
    container: AppContainer,
    request: VerticalPlayRequest,
    handoff: ShortPlayHandoff?,
): VerticalPlayViewModel {
    // lease 已持有真正 origin 的实例；配置重建后不能再访问旧 entry 的 ViewModelStore。
    if (handoff != null) return handoff.viewModel
    val deps = remember(container) { container.playerDeps() }
    val origin = request.origin
    val owner = origin ?: checkNotNull(LocalViewModelStoreOwner.current)
    val key = if (origin != null) shortPlayViewModelKey(request.vodId) else
        "vertical-${request.vodId}-${request.episodeNumber}-${request.resumeMs}"
    val viewModel: VerticalPlayViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = key,
        factory = VerticalPlayViewModel.factory(
            vodId = request.vodId,
            vodRepository = container.vodRepository,
            deps = deps,
            episodeNumber = request.episodeNumber,
            resumeMs = request.resumeMs,
            initialMetadata = request.initialMetadata,
        ),
    )
    return viewModel
}

private fun VerticalPlayRequest.contentOptions(
    interactions: ShortPlayInteractions,
    recommendations: ShortPlayRecommendations,
    nav: NavHostController,
    onComment: () -> Unit,
): VerticalPlayOptions = options.copy(
    // 「评论」是本目的地自己的弹层，其余动作（收藏 / 分享）仍归 ShortPlayInteractions。
    onAction = { label -> if (label == VERTICAL_ACTION_COMMENT) onComment() else interactions.onAction(label) },
    isFavorite = interactions.isFavorite,
    pendingAction = interactions.pendingAction,
    recommendations = recommendations.items,
    recommendationsLoading = recommendations.loading,
    recommendationsError = recommendations.error,
    onRecommendationsOpen = recommendations.load,
    onRecommendationsRetry = recommendations.load,
    onRecommendationClick = { id -> nav.navigate(verticalPlayRoute(id, FIRST_EPISODE_NUMBER)) },
)

private fun VerticalPlayRequest.openSeries(
    viewModel: VerticalPlayViewModel,
    playbackToken: Any,
    episode: Int,
    position: Long,
) {
    if (!viewModel.ownsPlayback(playbackToken)) return
    val inline = inlineMode
    if (inline != null) {
        if (inline.isCurrent()) inline.store.open(vodId, episode, viewModel, inline.pinPlayback)
        return
    }
    val origin = this.origin
    val canOpen = origin == null || beginShortPlayHandoff(origin, vodId, viewModel, episode)
    if (canOpen) options.onOpenSeries(episode, position)
}

private fun VerticalPlayRequest.accessRequest(
    state: VerticalPlayUiState,
    refresh: Int,
    active: Boolean,
): ShortPlayAccess = ShortPlayAccess(
    vodId, state.current?.number ?: episodeNumber, refresh,
    active = active && state.current != null,
)

private fun shouldShowShortPlayNotice(state: VerticalPlayUiState, active: Boolean): Boolean {
    val hasContent = !state.isLoading && state.episodes.isNotEmpty()
    return active && hasContent
}

private fun AppContainer.shortPlayUnlockOptions(result: AppResult<TrySeeGate>?): UnlockOptions {
    val gate = (result as? AppResult.Success)?.value
    return gate?.unlockOptions(adGateway.isReady(AdSlot.REWARD)) ?: UnlockOptions()
}

/**
 * 短剧常亮由单一应用宿主管理；交接时旧目的地退出不能清掉新页的常亮请求。
 * 使用宿主 View 的公开能力，不让两个目的地争用同一个 Window flag。
 */
@Composable
internal fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val previous = view.keepScreenOn
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = previous }
    }
}
