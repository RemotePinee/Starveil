package com.starveil.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import androidx.media3.common.Player

/** 位置采样间隔：进度条刷新、试看闸门与观看时长记账共用这一档。 */
private const val POSITION_TICK_MS = 500L

/**
 * ExoPlayer 的地址、播控与位置采样同步。
 *
 * 只做"状态 → 播放器"的推流，不改 [Media3Player] 的装载逻辑（双流合并留在那边）。
 */
@Composable
internal fun PlayerEngineEffects(engine: Media3Player, state: PlayerUiState, viewModel: PlayerViewModel) {
    val url = state.url
    val audioUrl = state.audioUrl
    val isPlaying = rememberUpdatedState(state.isPlaying)

    LaunchedEffect(url, audioUrl, state.headers) {
        if (url == null) return@LaunchedEffect
        engine.prepare(url, state.headers, audioUrl)
        if (isPlaying.value) engine.play()
    }
    //
    // pendingSeekMs 只在"新起播"时有值（构造参数 resume）；从别的页面切回来时引擎是
    // 重建的，pendingSeekMs 已经是 0，但 ViewModel 还留着上一次的 positionMs——
    // 不认它就会从头播（"切页面回来从头开始"就是这么来的）。
    LaunchedEffect(url, state.pendingSeekMs) {
        val resume = if (state.pendingSeekMs > 0L) state.pendingSeekMs else state.positionMs
        if (url != null && resume > 0L) {
            engine.seekTo(resume)
            viewModel.onSeekConsumed()
        }
    }
    LaunchedEffect(state.isPlaying) {
        if (state.isPlaying) engine.play() else engine.pause()
    }
    LaunchedEffect(state.speed) { engine.setSpeed(state.speed) }
    LaunchedEffect(url) {
        while (url != null) {
            // 引擎刚重建时是 IDLE，此刻 position 恒为 0：把 0 上报给 UI，
            // 进度条会先跳回开头再被 seek 拉回断点（"切回来闪一下"就是这么来的）。
            if (engine.mediaPlayer.playbackState != Player.STATE_IDLE) {
                viewModel.onProgress(engine.currentPositionMs, engine.durationMs, isPlaying.value)
            }
            delay(POSITION_TICK_MS)
        }
    }
    PlayerBridgeEffect(engine, viewModel)
}

/** 播放器回调 → ViewModel。业务判断留在 ViewModel，这里只做转发。 */
@Composable
private fun PlayerBridgeEffect(engine: Media3Player, viewModel: PlayerViewModel) {
    val bridge = remember(viewModel) { PlayerBridge(viewModel) }
    DisposableEffect(engine, bridge) {
        engine.setListener(bridge)
        onDispose { engine.setListener(null) }
    }
}

private class PlayerBridge(private val viewModel: PlayerViewModel) : Media3Player.Listener {

    override fun onPlaybackStateChanged(isBuffering: Boolean) = viewModel.onBufferingChanged(isBuffering)

    override fun onEnded() = viewModel.onPlaybackEnded()

    override fun onError(message: String) = viewModel.onPlaybackError(message)
}

/** 回调集合 → ViewModel；拖动进度必须同时写页面状态与播放器位置。 */
internal fun playerActions(viewModel: PlayerViewModel, engine: Media3Player): PlayerActions = PlayerActions(
    onPlayPause = viewModel::onPlayPauseClick,
    onSeekTo = { positionMs -> seekBoth(viewModel, engine, positionMs) },
    onSpeedSelected = viewModel::onSpeedSelected,
    onFullscreenToggle = viewModel::onFullscreenToggle,
    onSwitchLine = viewModel::onSwitchLineClick,
    onSourceSelected = viewModel::onSourceSelected,
    onEpisodeSelected = viewModel::onEpisodeSelected,
    onRetry = viewModel::onRetryClick,
    onUpgrade = viewModel::onUpgradeClick,
    onBuy = viewModel::onBuyClick,
    onSendDanmu = { text, colorHex -> viewModel.sendDanmu(text, colorHex) },
)

/** 拖动进度：页面状态与播放器位置必须同时更新，否则下一次采样会把指针拽回去。 */
internal fun seekBoth(viewModel: PlayerViewModel, engine: Media3Player, positionMs: Long) {
    viewModel.onSeekTo(positionMs)
    engine.seekTo(positionMs)
}
