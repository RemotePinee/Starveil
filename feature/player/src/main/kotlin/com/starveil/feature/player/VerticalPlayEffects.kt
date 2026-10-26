package com.starveil.feature.player

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val VERTICAL_POSITION_TICK_MS = 500L

/** 预取后面几集：两集是命中率与流量的折中（再多会和当前集抢带宽）。 */
private const val PREFETCH_AHEAD = 2

/** 只保留一个同步播控出口；URL、重试、可见性变化都按 prepare → seek → play 排序。 */
@Composable
internal fun VerticalEngineEffects(
    playback: VerticalPlaybackOwner,
    state: VerticalPlayUiState,
    viewModel: VerticalPlayViewModel,
    options: VerticalPlayOptions = VerticalPlayOptions(),
    speed: Float = 1f,
    pagerState: PagerState? = null,
    playbackToken: Any = viewModel.defaultPlaybackToken,
) {
    val engine = playback.engine
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState == Lifecycle.State.RESUMED) }
    val latestOptions = rememberUpdatedState(options)
    val controller = remember(playback, playbackToken) { Any() }
    val bridge = remember(engine, viewModel, lifecycle, pagerState, playbackToken) {
        VerticalPlayerBridge(
            playback, viewModel,
            VerticalPlayGate(
                currentOptions = { latestOptions.value },
                isForegroundReady = {
                    lifecycle.currentState == Lifecycle.State.RESUMED &&
                        pagerState.isReadyFor(viewModel.state.value, latestOptions.value.preview)
                },
                ownsPlayback = {
                    viewModel.ownsPlayback(playbackToken) && playback.controller.owns(controller)
                },
                playbackToken = playbackToken,
            ),
            controller,
        )
    }
    val enabled = canPlayVertical(options.active, resumed, pagerState.isReadyFor(state, options.preview))
    DisposableEffect(engine, lifecycle, bridge) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState == Lifecycle.State.RESUMED
            if (!resumed) bridge.suspendPlayback()
        }
        bridge.attach()
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            bridge.detach(lifecycle)
        }
    }
    SideEffect {
        bridge.update(speed)
    }
    // 预取接下来两集的清单与首个分片：用户滑得快时只预取一集来不及落地，
    // 两集能把命中率从约 1/4 抬起来（实测命中时起播 260ms，未命中 2.1s）。
    // 只在切片后触发，避免和当前集抢带宽。
    LaunchedEffect(state.currentIndex, state.playingUrl) {
        val upcoming = state.episodes.drop(state.currentIndex + 1).take(PREFETCH_AHEAD)
        upcoming.forEach { engine.prefetch(it.url, state.headers) }
    }
    LaunchedEffect(bridge, state.mediaSource(), enabled) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            bridge.sample()
            delay(VERTICAL_POSITION_TICK_MS)
        }
    }
}

/** 单一出口协调 VM 请求与真实落页；idle 变化也会唤醒，不能只监听 settledPage。 */
@Composable
internal fun VerticalPagerSync(
    state: VerticalPlayUiState,
    pagerState: PagerState,
    viewModel: VerticalPlayViewModel,
    playbackToken: Any = viewModel.defaultPlaybackToken,
    options: VerticalPlayOptions = VerticalPlayOptions(),
) {
    val count = state.episodes.size
    val latest = rememberUpdatedState(state)
    LaunchedEffect(count, pagerState, viewModel, playbackToken, options.preview, options.transitioning) {
        if (count == 0 || !viewModel.ownsPlayback(playbackToken)) return@LaunchedEffect
        if (options.preview || options.transitioning) {
            snapshotFlow { latest.value.currentIndex }.collect { page ->
                if (viewModel.ownsPlayback(playbackToken)) {
                    pagerState.requestEpisodeAlignment(page.coerceIn(0, count - 1))
                }
            }
            return@LaunchedEffect
        }
        // 同集切模式已经对齐时必须无操作，不能用重复 scrollToPage 短暂关掉播放门禁。
        val initial = latest.value.currentIndex.coerceIn(0, count - 1)
        if (!pagerState.isAlignedWith(initial)) pagerState.scrollToPage(initial)
        val coordinator = VerticalPagerCoordinator(pagerState.settledPage)
        snapshotFlow { pagerState.snapshot(latest.value.currentIndex) }
            .collect {
                if (viewModel.ownsPlayback(playbackToken)) {
                    coordinateVerticalPager(pagerState, viewModel, coordinator, playbackToken)
                }
            }
    }
}

/** 播放器回调 → ViewModel。业务判断留在 ViewModel，这里只转发事实。 */
private class VerticalPlayerBridge(
    private val playback: VerticalPlaybackOwner,
    private val viewModel: VerticalPlayViewModel,
    private val gate: VerticalPlayGate,
    private val controller: Any,
) : Media3Player.Listener {
    private val engine = playback.engine
    private val session = playback.session

    fun attach() {
        if (!viewModel.ownsPlayback(gate.playbackToken)) return
        playback.controller.claim(controller)
        engine.setListener(this)
    }

    fun detach(lifecycle: Lifecycle) {
        suspendPlayback()
        engine.clearListener(this)
        val mayRelease = gate.ownsPlayback() && !viewModel.hasPreviewHandoff
        if (mayRelease && lifecycle.currentState == Lifecycle.State.RESUMED) viewModel.playback.release(playback)
        playback.controller.clear(controller)
    }

    fun update(speed: Float) {
        if (!gate.ownsPlayback()) return
        // 应用变更时读取真实生命周期，不能被 ON_PAUSE 后的旧 effect 重新打开。
        val active = gate.allowsPlayback()
        if (!active) suspendPlayback()
        session.update(viewModel.state.value, active, speed, gate.playbackToken)
    }

    private fun visible(): Boolean =
        gate.allowsPlayback() && session.canObserve(viewModel.state.value)

    fun sample() {
        if (!visible()) return
        viewModel.onBufferingChanged(engine.mediaPlayer.playbackState == Player.STATE_BUFFERING)
        viewModel.onProgress(engine.currentPositionMs, engine.durationMs, engine.mediaPlayer.isPlaying)
        val ended = engine.mediaPlayer.playbackState == Player.STATE_ENDED
        if (ended && session.canPlay(viewModel.state.value)) {
            if (gate.options.preview) viewModel.onPreviewPlaybackEnded(gate.options.onOpenSeries)
            else viewModel.onPlaybackEnded()
        }
    }

    fun suspendPlayback() {
        if (!gate.ownsPlayback() || !session.suspendPlayback(gate.playbackToken)) return
        if (session.isCurrent(viewModel.state.value)) {
            viewModel.onProgress(engine.currentPositionMs, engine.durationMs, isPlaying = false)
        }
        viewModel.onPlaybackSuspended()
    }

    override fun onPlaybackStateChanged(isBuffering: Boolean) {
        if (!gate.ownsPlayback()) return
        if (isBuffering != (engine.mediaPlayer.playbackState == Player.STATE_BUFFERING)) return
        if (session.isCurrent(viewModel.state.value)) viewModel.onBufferingChanged(isBuffering)
    }

    override fun onEnded() = sample()

    override fun onError(message: String) {
        if (!gate.ownsPlayback()) return
        val currentError = engine.mediaPlayer.playerError?.errorCodeName
        if (currentError == message && session.isCurrent(viewModel.state.value)) viewModel.onPlaybackError(message)
    }
}

/**
 * 桥接层只需要两件事：当前 options，以及"此刻能不能播"。
 *
 * 三个输入都传 lambda 而不是布尔值：由调用点决定何时读，
 * [VerticalEngineEffects] 的 SideEffect 每次应用变更都要读真实生命周期。
 */
private class VerticalPlayGate(
    private val currentOptions: () -> VerticalPlayOptions,
    private val isForegroundReady: () -> Boolean,
    val ownsPlayback: () -> Boolean,
    val playbackToken: Any,
) {
    val options: VerticalPlayOptions get() = currentOptions()

    fun allowsPlayback(): Boolean = ownsPlayback() && options.active && isForegroundReady()
}

/** 预览播完后原地展开并续播下一集；展开失败或已是末集时停在已完成状态。 */
internal fun VerticalPlayViewModel.onPreviewPlaybackEnded(openSeries: (Int, Long) -> Unit) {
    val completed = state.value
    val canAdvance = completed.isPlaying && !completed.isBlocked && completed.errorMessage == null
    if (!canAdvance) return
    onPlaybackEnded(preview = true)
    if (completed.currentIndex >= completed.episodes.lastIndex) return
    openSeries(completed.current?.number ?: FIRST_EPISODE_NUMBER, completed.positionMs)
    // 只有成功原地展开才续集；入口失效或导航交接失败时不在推荐态偷偷切集。
    if (hasPreviewHandoff && ownsPlayback(defaultPlaybackToken)) onPlaybackEnded()
}

private fun PagerState.snapshot(requestedPage: Int): VerticalPagerSnapshot =
    VerticalPagerSnapshot(requestedPage, settledPage, isScrollInProgress)

private fun PagerState?.isReadyFor(state: VerticalPlayUiState, preview: Boolean): Boolean =
    preview || (this?.snapshot(state.currentIndex)?.isPlaybackReady ?: true)

private fun PagerState.isAlignedWith(page: Int): Boolean =
    currentPage == page && currentPageOffsetFraction == 0f && !isScrollInProgress

/** preview 没有 pager 布局，也必须立即同步逻辑坐标；不得等首次 layout 才恢复播放。 */
private fun PagerState.requestEpisodeAlignment(page: Int) {
    if (!isAlignedWith(page)) requestScrollToPage(page)
}

/** 预览开关、真实前台与 pager 落稳同时成立才允许播；改动只在这里收口。 */
private fun canPlayVertical(active: Boolean, isResumed: Boolean, isPageReady: Boolean): Boolean =
    active && isResumed && isPageReady

private suspend fun coordinateVerticalPager(
    pagerState: PagerState,
    viewModel: VerticalPlayViewModel,
    coordinator: VerticalPagerCoordinator,
    playbackToken: Any,
) = coroutineScope {
    var action = coordinator.next(pagerState.snapshot(viewModel.state.value.currentIndex))
    while (action is VerticalPagerAction.ScrollToEpisode) {
        val target = action.page
        // 手势可以取消动画子任务，但不能取消协调器；结束后重读实际落页，不靠丢失的 flow 边沿。
        launch { pagerState.animateScrollToPage(target) }.join()
        val settled = pagerState.snapshot(viewModel.state.value.currentIndex)
        coordinator.onProgramScrollFinished(target, settled)
        action = coordinator.next(settled)
    }
    if (action is VerticalPagerAction.SelectEpisode && viewModel.ownsPlayback(playbackToken)) {
        viewModel.onPageChanged(action.page)
    }
}
