package com.starveil.app.navigation

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.Play
import com.starveil.app.AppContainer
import com.starveil.app.ads.RewardRequestContext
import com.starveil.core.common.ad.AdSlot
import com.starveil.core.designsystem.component.LocalRewardAd
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.Vod
import com.starveil.feature.player.PlayerEvent
import com.starveil.feature.player.Media3Player
import com.starveil.feature.player.PlayerRoute
import com.starveil.feature.player.UnlockOptions
import com.starveil.feature.player.rememberSettledFullscreen
import com.starveil.feature.player.unlockOptions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.starveil.feature.player.PlayerViewModel
import kotlinx.coroutines.launch
import com.starveil.core.common.AppResult

/** 半屏播放器比例，与详情页头图一致（`DetailHero` 的 HERO_ASPECT）。 */
private const val PLAYER_ASPECT_RATIO = 16f / 9f

/** 未起播时压在海报上的遮罩浓度：压暗到能看清播放键，又不至于看不出是海报。 */
private const val POSTER_SCRIM_ALPHA = 0.35f

data class InlinePlayRequest(
    val sourceIndex: Int,
    val episodeIndex: Int,
    /** 断点续播位置（毫秒）；0 表示从头。 */
    val resumeMs: Long = 0L,
)

/**
 * 内嵌播放器的回调集合。
 *
 * 与 [PlayerEntry] 一样收成一个对象：Composable 参数有 5 个上限（AGENTS.md 第 2 节），
 * 而这几件事的归属者不同（起播归详情页、购买归支付路由、全屏归详情页顶栏）。
 */
class InlinePlayerCallbacks(
    /** 未起播时点海报上的大播放键。 */
    val onStart: () -> Unit = {},
    /** 点「积分兑换」：:app 转支付页的买断模式，与独立播放页同一条路由。 */
    val onPurchase: (vodId: Int, episodeNumber: Int, sourceIndex: Int) -> Unit = { _, _, _ -> },
    /** 点「开通会员」：:app 转会员页。 */
    val onUpgrade: () -> Unit = {},
    /** 全屏开关：详情页据此收起顶栏。 */
    /** 全屏开关：详情页据此收起顶栏。 */
    val onFullscreenChange: (Boolean) -> Unit = {},
    /** 当前播放集变化（含自动下一集）：详情页据此把选集条切到同一集。 */
    val onEpisodeChanged: (Int) -> Unit = {},
)

/**
 * 内嵌播放器的一屏：探测结果 + 提前建好的播放器。
 *
 * 打包成对象是因为 [InlinePlayerRoute] 的参数已经到 detekt 的 5 个上限，
 * 而这两个值总是一起产生（探测一完成就有播放器可用）。
 */
private class InlinePlayerStage(
    val load: GateLoad.Ready,
    /** 探测阶段就建好，由 [InlinePlayback] 持有并负责释放。 */
    val engine: Media3Player,
)

/**
 * 详情页内嵌的半屏播放器（优酷式「详情 + 播放器」）。
 *
 * 由 :app 作为 `DetailScreen` 的头图插槽传入，详情模块不认识播放器（AGENTS.md 第 1 节）。
 * 三层：
 * 1. 未起播：海报（详情页自己画）+ 大播放键，不建 ViewModel、不占播放器；
 * 2. 起播后：16:9 宿主，内部复用 [PlayerRoute]，试看 / 选集 / 弹幕浮层与独立播放页完全一致；
 * 3. 全屏：同一个 ViewModel、同一个 Media3Player 撑满整屏，退全屏回到 16:9 半屏。
 *
 * @param request 当前起播位置；换集会换 ViewModel（试看闸门是构造期注入的），
 *   但 [PlayerRoute] 的调用点不变，播放器实例因此不会重建
 */
@Composable
fun InlineDetailPlayer(
    container: AppContainer,
    vod: Vod,
    request: InlinePlayRequest?,
    modifier: Modifier = Modifier,
    callbacks: InlinePlayerCallbacks = InlinePlayerCallbacks(),
) {
    if (request == null) {
        InlinePoster(modifier = modifier, onPlay = callbacks.onStart)
    } else {
        InlinePlayback(
            container = container,
            request = PlayerRequest(
                vod = vod,
                sourceIndex = request.sourceIndex,
                episodeIndex = request.episodeIndex,
                resumeMs = request.resumeMs,
            ),
            modifier = modifier,
            callbacks = callbacks,
        )
    }
}

/** 起播前的海报层：海报由详情页画，这里只压一层遮罩和播放键。 */
@Composable
private fun InlinePoster(modifier: Modifier, onPlay: () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(PLAYER_ASPECT_RATIO)
            .clickable(onClick = onPlay)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = POSTER_SCRIM_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(Spacing.xxl * 2)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = PhosphorIcons.Fill.Play,
                contentDescription = "播放",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(IconSize.lg),
            )
        }
    }
}

/** 闸门在飞时的占位：与播放器同尺寸，避免加载完成瞬间页面跳动。 */
@Composable
private fun InlineLoading(modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(PLAYER_ASPECT_RATIO)
            .background(starveilExtraColors.scrimStrong),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun InlinePlayback(
    container: AppContainer,
    request: PlayerRequest,
    modifier: Modifier,
    callbacks: InlinePlayerCallbacks,
) {
    val context = LocalContext.current
    // 探测地址要几百毫秒，而建播放器又要几百毫秒——原先两件事串着做，用户干等。
    // 提到 when 外面，探测一开始播放器就先搭起来，等 Ready 时直接 prepare。
    val engine = remember(context) { Media3Player(context) }
    // 提前建就要提前管释放：用户可能探测没完就返回，那时 PlayerRoute 还没组合过。
    DisposableEffect(engine) { onDispose { engine.release() } }
    when (val load = rememberGate(container, request)) {
        GateLoad.Loading -> InlineLoading(modifier)
        is GateLoad.Ready -> InlinePlayerRoute(
            container = container,
            stage = InlinePlayerStage(load = load, engine = engine),
            modifier = modifier,
            callbacks = callbacks,
        )
    }
}

/** 详情页内嵌播放器：回到前台时重查试看闸门（与播放页同一口径，第一次不重复请求）。 */
@Composable
private fun RecheckInlineGateOnResume(
    container: AppContainer,
    request: PlayerRequest,
    viewModel: PlayerViewModel,
) {
    val scope = rememberCoroutineScope()
    var resumedOnce by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit, viewModel) {
        if (resumedOnce) {
            scope.launch {
                // 同上：按当前播放的这一集查
                val current = viewModel.state.value
                val result = container.accessRepository.checkTrySee(
                    vodId = request.vod.id,
                    episodeNumber = episodeNumberOf(request.vod, current.sourceIndex, current.episodeIndex),
                    sourceIndex = current.sourceIndex,
                )
                (result as? AppResult.Success)?.value?.let { gate ->
                    viewModel.updateAccess(gate.access, gate.trySeeMinutes)
                }
            }
        } else {
            resumedOnce = true
        }
        onPauseOrDispose { }
    }
}

@Composable
private fun InlinePlayerRoute(
    container: AppContainer,
    stage: InlinePlayerStage,
    modifier: Modifier,
    callbacks: InlinePlayerCallbacks,
) {
    val load = stage.load
    val engine = stage.engine
    val viewModel = rememberPlayerViewModel(container, load)
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 播放器内换集（选集弹层）只改会话，不动 request；购买要按「当前这一集」下单
    val current by rememberUpdatedState(state)
    val context = LocalContext.current
    // 等系统真的横过来再铺满：否则会先"竖屏铺满"再被转过去，看着像卡两下
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // 退出全屏时 state.isFullscreen 立刻变 false，但窗口还要 200-400ms 才转回竖屏。
    // 直接拿它算 fillStage，那几百毫秒里会排一次"横屏窗口 + 16:9 容器"——就是返回时
    // 那下画面被拉变形的原因。这里跟 PlayerScreen 用同一个栅栏：真的转完（或超宽限）才算退出。
    val settledFullscreen = rememberSettledFullscreen(state.isFullscreen, isLandscape)
    val fillStage = settledFullscreen && isLandscape
    val shape = if (fillStage) {
        Modifier.fillMaxSize()
    } else {
        Modifier.fillMaxWidth().aspectRatio(PLAYER_ASPECT_RATIO)
    }
    // 方向切换是 Activity 的事；"收起详情正文"要等真的横过来（fillStage 为真）再通知，
    // 由 LaunchedEffect 跟着转屏走，而不是只在点击那一刻判断一次
    LaunchedEffect(state.isFullscreen) { context.applyPlayerFullscreen(state.isFullscreen) }
    LaunchedEffect(fillStage) { callbacks.onFullscreenChange(fillStage) }
    // 全屏由播放页报告，方向由宿主 Activity 切：这里只是转发给 Activity 与详情页顶栏
    DisposableEffect(Unit) {
        onDispose {
            context.applyPlayerFullscreen(false)
            callbacks.onFullscreenChange(false)
        }
    }
    LaunchedEffect(state.isFullscreen) { context.applyPlayerFullscreen(state.isFullscreen) }
    LaunchedEffect(fillStage) { callbacks.onFullscreenChange(fillStage) }
    // 播放器内部换集（含自动下一集）时通知详情页，选集条跟着切，不然两边显示对不上
    LaunchedEffect(state.episodeIndex) { callbacks.onEpisodeChanged(state.episodeIndex) }
    // 支付/积分买断回来重查一次权限：详情页内嵌播放器不会重建，闸门必须自己刷
    RecheckInlineGateOnResume(container = container, request = load.request, viewModel = viewModel)
    val refreshGate = rememberGateRefresh(container, load.request, viewModel)
    val showRewardAd = LocalRewardAd.current
    val adScope = rememberCoroutineScope()
    Box(modifier = modifier.then(shape)) {
        PlayerRoute(
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            unlock = load.gate?.unlockOptions(container.adGateway.isReady(AdSlot.REWARD))
                ?: UnlockOptions(),
            // 看完广告重查闸门，放不放行由服务端说了算；
            // 请求前先声明这一集的身份，平台的 SSV 回调才写得出这个用户的解锁记录
            onWatchAd = {
                val episode = episodeNumberOf(load.request.vod, current.sourceIndex, current.episodeIndex)
                RewardRequestContext.begin(container.sessionSnapshot()?.userId, load.request.vod.id, episode)
                showRewardAd {
                    RewardRequestContext.clear()
                    adScope.launch { refreshGate() }
                }
            },
            onEvent = { event ->
                when (event) {
                    is PlayerEvent.RequestPurchase -> callbacks.onPurchase(
                        load.request.vod.id,
                        episodeNumberOf(load.request.vod, current.sourceIndex, current.episodeIndex),
                        current.sourceIndex,
                    )

                    PlayerEvent.RequestUpgrade -> callbacks.onUpgrade()
                    else -> context.showPlayerEvent(event)
                }
            },
            // 横竖屏与详情页收起都在上面的 LaunchedEffect 里处理，这里不再重复转发
            onFullscreenChange = { fullscreen -> context.applyPlayerFullscreen(fullscreen) },
        )
    }
}
