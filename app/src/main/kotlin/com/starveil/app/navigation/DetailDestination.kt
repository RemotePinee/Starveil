package com.starveil.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.starveil.app.AppContainer
import com.starveil.core.common.AppResult
import com.starveil.feature.detail.ARG_EPISODE_INDEX
import com.starveil.feature.detail.ARG_SOURCE_INDEX
import com.starveil.feature.detail.ARG_VOD_ID
import com.starveil.feature.detail.DETAIL_INDEX_UNSET
import com.starveil.feature.detail.DETAIL_ROUTE_PATTERN
import com.starveil.feature.detail.DetailDeps
import com.starveil.feature.detail.DetailRoute
import com.starveil.feature.detail.DetailUiState
import com.starveil.feature.detail.DetailViewModel
import com.starveil.feature.detail.detailRoute
import com.starveil.feature.pay.PAY_MODE_EPISODE
import com.starveil.feature.pay.payRoute
import kotlinx.coroutines.launch

/** 起播位置未设置：头图位置保持海报。 */
private const val NOT_STARTED = -1

/** 秒 → 毫秒。 */
private const val MILLIS_PER_SECOND = 1_000L

/**
 * 详情页（优酷式：详情与播放器同页）。
 *
 * 页面在 `:feature:detail`，播放器在 `:feature:player`，两者不能互相依赖
 * （AGENTS.md 第 1 节），所以"内嵌播放器"只能在 :app 里组合：
 * `DetailRoute` 的头图插槽塞 [InlineDetailPlayer]，起播请求由本文件持有。
 *
 * 点「播放」/ 选集不跳独立播放路由；`playerRoute` 保留给深链与播放页入口。
 * 竖屏短剧走 `:feature:shortplay` 的 `VerticalPlay*`，与这里完全两套。
 */
internal fun NavGraphBuilder.detailDestination(nav: NavHostController, container: AppContainer) {
    composable(
        route = DETAIL_ROUTE_PATTERN,
        arguments = listOf(
            navArgument(ARG_VOD_ID) { type = NavType.IntType },
            navArgument(ARG_SOURCE_INDEX) {
                type = NavType.IntType
                defaultValue = DETAIL_INDEX_UNSET
            },
            navArgument(ARG_EPISODE_INDEX) {
                type = NavType.IntType
                defaultValue = DETAIL_INDEX_UNSET
            },
        ),
        // 详情页是首页点进去的主流程：走 push 转场（右推入 / 右退出），
        // 不再是整站默认的纯淡入，否则从首页点进来会像硬切
        enterTransition = { pushEnter() },
        exitTransition = { pushExit() },
        popEnterTransition = { pushPopEnter() },
        popExitTransition = { pushPopExit() },
    ) { entry ->
        DetailEntry(
            nav = nav,
            container = container,
            vodId = entry.arguments?.getInt(ARG_VOD_ID) ?: 0,
            initialSource = entry.arguments?.getInt(ARG_SOURCE_INDEX) ?: DETAIL_INDEX_UNSET,
            initialEpisode = entry.arguments?.getInt(ARG_EPISODE_INDEX) ?: DETAIL_INDEX_UNSET,
        )
    }
}

/** 目的地正文。抽出来是为了让 [detailDestination] 保持一屏能读完（detekt LongMethod）。 */
@Composable
private fun DetailEntry(
    nav: NavHostController,
    container: AppContainer,
    vodId: Int,
    // 起播位置由路由参数决定，缺省时与调用点用同一个哨兵值；给默认值也让这个
    // 函数满足 detekt 的参数上限（detekt.yml 里 ignoreDefaultParameters: true）。
    initialSource: Int = DETAIL_INDEX_UNSET,
    initialEpisode: Int = DETAIL_INDEX_UNSET,
) {
    val detailViewModel = rememberDetailViewModel(container, vodId)
    val fullscreenController = LocalFullscreenController.current
    val scope = rememberCoroutineScope()
    // 内嵌播放器的起播位置。放 saveable 里：转屏走的是 configChanges，
    // 但进程回收后重建仍要回到"已经在播"而不是海报。
    var playSource by rememberSaveable(vodId) { mutableStateOf(NOT_STARTED) }
    var playEpisode by rememberSaveable(vodId) { mutableStateOf(0) }
    // 断点续播：起播前从服务端进度读回，交给播放器定位一次
    var resumeMs by rememberSaveable(vodId) { mutableLongStateOf(0L) }
    DetailRoute(
        viewModel = detailViewModel,
        onBack = { nav.popBackStack() },
        onPlay = { sourceIndex, episodeIndex ->
            playSource = sourceIndex
            playEpisode = episodeIndex
            resumeMs = 0L
        },
        onRecommendClick = { recommendId -> nav.navigate(detailRoute(recommendId)) },
        heroSlot = {
            // 从观看记录进来：先按记录预选上次那条线路那一集，点播放才命中断点
            // （多源影片尤其需要，否则会跳回第一源第 1 集）
            LaunchedEffect(content.vod.id) {
                if (initialSource >= 0 && initialEpisode >= 0) {
                    detailViewModel.selectSource(initialSource)
                    detailViewModel.selectEpisode(initialEpisode)
                }
            }

            // 切源：正在播就按新源的同一集接着播，不用再点一次集数
            LaunchedEffect(content.sourceIndex) {
                if (playSource != NOT_STARTED && content.sourceIndex != playSource) {
                    resumeMs = 0L
                    playEpisode = content.episodeIndex
                    playSource = content.sourceIndex
                }
            }
            InlineDetailHero(
                nav = nav,
                container = container,
                state = InlineHeroState(
                    content = content,
                    playSource = playSource,
                    playEpisode = playEpisode,
                    resumeMs = resumeMs,
                ),
                callbacks = InlineHeroCallbacks(
                    onStart = { source, episode ->
                        // 先把断点读回来再起播：播放器 VM 一旦按 resumeMs=0 建好，
                        // 后面再改 request 也不会重建，续播就永远不生效
                        scope.launch {
                            // 读精确断点（服务端 cuican_vlog），不用最近 10 条的列表
                            val stored = (container.playbackRepository.progressOf(vodId) as? AppResult.Success)?.value
                            val sameEpisode = stored != null &&
                                stored.sourceIndex == source &&
                                stored.episodeIndex == episode &&
                                stored.resumable
                            resumeMs = if (sameEpisode) stored.positionSec * MILLIS_PER_SECOND else 0L
                            playSource = source
                            playEpisode = episode
                        }
                    },
                    onFullscreenChange = { fullscreen -> fullscreenController.set(fullscreen) },
                    onEpisodeChanged = { index ->
                        // 播放器（含自动下一集）换集 → 详情页选集条与高亮同步
                        playEpisode = index
                        resumeMs = 0L
                        detailViewModel.selectEpisode(index)
                    },
                ),
            )
        },
    )
}

/** 详情页头图区的内嵌播放器；把回调与路由细节留在 :app，detail 只认插槽。 */
private data class InlineHeroState(
    val content: DetailUiState.Content,
    val playSource: Int,
    val playEpisode: Int,
    /** 断点续播位置（毫秒）；0 从头。 */
    val resumeMs: Long = 0L,
)

private data class InlineHeroCallbacks(
    val onStart: (Int, Int) -> Unit,
    val onFullscreenChange: (Boolean) -> Unit,
    /** 播放器内部换集（含自动下一集）时同步详情页的选集高亮。 */
    val onEpisodeChanged: (Int) -> Unit,
)

@Composable
private fun rememberDetailViewModel(container: AppContainer, vodId: Int): DetailViewModel = viewModel(
    factory = DetailViewModel.factory(
        deps = DetailDeps(
            vodRepository = container.vodRepository,
            favoriteRepository = container.favoriteRepository,
            authRepository = container.authRepository,
            commentRepository = container.commentRepository,
        ),
        vodId = vodId,
    ),
)

@Composable
private fun InlineDetailHero(
    nav: NavHostController,
    container: AppContainer,
    state: InlineHeroState,
    callbacks: InlineHeroCallbacks,
) {
    val content = state.content
    val playSource = state.playSource
    val playEpisode = state.playEpisode
    InlineDetailPlayer(
        container = container,
        vod = content.vod,
        request = if (playSource == NOT_STARTED) {
            null
        } else {
            InlinePlayRequest(
                sourceIndex = playSource,
                episodeIndex = playEpisode,
                resumeMs = state.resumeMs,
            )
        },
        callbacks = InlinePlayerCallbacks(
            onStart = { callbacks.onStart(content.sourceIndex, content.episodeIndex) },
            onPurchase = { purchaseVod, episodeNumber, sourceIndex ->
                nav.navigate(
                    payRoute(
                        mode = PAY_MODE_EPISODE,
                        vodId = purchaseVod,
                        episodeNumber = episodeNumber,
                        sourceIndex = sourceIndex,
                    ),
                )
            },
            onUpgrade = { nav.navigate(payRoute()) },
            onFullscreenChange = callbacks.onFullscreenChange,
            onEpisodeChanged = callbacks.onEpisodeChanged,
        ),
    )
}
