package com.starveil.feature.live

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Pause
import com.adamglin.phosphoricons.regular.Play
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.common.liveErrorMessage
import com.starveil.core.designsystem.token.Spacing

/**
 * 直播流播放引擎（Media3 薄封装，只做装载与状态上报，不含业务判断）。
 *
 * 历史版本没有进程内播放器：点卡片一律调起 X5 网页，
 * 连 mp4/m3u8 也只是"少传一个标题"。这里直接用 `LiveChannel.url` 起播：
 * HLS 走 [HlsMediaSource]，其余（mp4/flv）走 [ProgressiveMediaSource]。
 * 直播没有进度条、没有 seek、没有倍速，所以这些方法一个都不暴露。
 */
@OptIn(UnstableApi::class)
private class LivePlayerEngine(context: Context) {

    /** 播放器回调，只报事实；"缓冲/播放/错误"如何落成 UI 状态由 ViewModel 决定。 */
    interface Listener {
        fun onBuffering()
        fun onPlaying()
        fun onError(message: String)
    }

    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()
    private var listener: Listener? = null

    private val mediaListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> listener?.onBuffering()
                Player.STATE_READY -> listener?.onPlaying()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // 只上报事实：错误码在这里翻成人话，UI 不再看到 ERROR_CODE_* 常量
            listener?.onError(liveErrorMessage(error.errorCodeName, httpStatusOf(error)))
        }
    }

    init {
        player.addListener(mediaListener)
    }

    fun setListener(value: Listener?) {
        listener = value
    }

    /** 装载地址并立即起播；重复调用会替换媒体源（重试靠它）。 */
    fun prepare(url: String) {
        val item = MediaItem.fromUri(Uri.parse(url))
        val dataSource = DefaultHttpDataSource.Factory()
        val source = if (isHls(url)) {
            HlsMediaSource.Factory(dataSource).createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(dataSource).createMediaSource(item)
        }
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }

    /** 视频输出交给官方 PlayerSurface 管理，不另建播放器实例。 */
    val mediaPlayer: Player get() = player

    fun play() {
        player.playWhenReady = true
    }

    fun pause() {
        player.playWhenReady = false
    }

    fun release() {
        listener = null
        player.removeListener(mediaListener)
        player.release()
    }

    /** HLS 判定：旧接口给的是 `xxx.m3u8` 直链，也可能带鉴权查询串，先截断再比后缀。 */
    private fun isHls(url: String): Boolean =
        url.substringBefore('?').endsWith(HLS_SUFFIX, ignoreCase = true)

    private companion object {
        const val HLS_SUFFIX = ".m3u8"
    }
}

/**
 * 直播播放区：视频面 + Compose 自绘控件。
 *
 * 不用 media3 的 PlayerView（本模块只带 media3-ui-compose），控制条只有播放/暂停与重试，
 * 与直播场景一一对应。
 */
@Composable
fun LivePlayer(
    state: LivePlayerUiState,
    actions: LiveActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val engine = remember { LivePlayerEngine(context.applicationContext) }
    LivePlayerEffects(engine = engine, state = state, actions = actions)
    PlayerStage(engine = engine, state = state, actions = actions, modifier = modifier)
}

/** 把播放器回调接到 ViewModel，并按 (url, attempt) 装载媒体源。 */
@Composable
private fun LivePlayerEffects(
    engine: LivePlayerEngine,
    state: LivePlayerUiState,
    actions: LiveActions,
) {
    val currentActions by rememberUpdatedState(actions)
    DisposableEffect(engine) {
        val listener = object : LivePlayerEngine.Listener {
            override fun onBuffering() = currentActions.onPlaybackBuffering()
            override fun onPlaying() = currentActions.onPlaybackStarted()
            override fun onError(message: String) = currentActions.onPlaybackError(message)
        }
        engine.setListener(listener)
        onDispose {
            engine.setListener(null)
            engine.release()
        }
    }
    val url = state.channel?.url
    LaunchedEffect(url, state.attempt) {
        if (!url.isNullOrBlank()) engine.prepare(url)
    }
}

@Composable
private fun PlayerStage(
    engine: LivePlayerEngine,
    state: LivePlayerUiState,
    actions: LiveActions,
    modifier: Modifier = Modifier,
) {
    var isPaused by remember(state.channel?.id, state.attempt) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(VIDEO_ASPECT_RATIO)
            .background(MaterialTheme.colorScheme.scrim),
    ) {
        VideoSurface(engine = engine, modifier = Modifier.fillMaxSize())
        PlayerOverlay(
            playback = state.playback,
            isPaused = isPaused,
            onTogglePause = {
                isPaused = !isPaused
                if (isPaused) engine.pause() else engine.play()
            },
            onRetry = actions.onRetryPlayer,
        )
    }
}

/** TextureView 与页面共用 View 合成路径，跟随导航一起移动和淡出。 */
@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(engine: LivePlayerEngine, modifier: Modifier = Modifier) {
    PlayerSurface(
        player = engine.mediaPlayer,
        surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
        modifier = modifier,
    )
}

@Composable
private fun PlayerOverlay(
    playback: LivePlaybackState,
    isPaused: Boolean,
    onTogglePause: () -> Unit,
    onRetry: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (playback) {
            is LivePlaybackState.Buffering -> CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onPrimary,
            )

            is LivePlaybackState.Error -> PlaybackError(message = playback.message, onRetry = onRetry)

            is LivePlaybackState.Idle -> Unit

            is LivePlaybackState.Playing -> if (isPaused) PlayButton(onClick = onTogglePause)
        }
        if (playback is LivePlaybackState.Playing) {
            PauseButton(
                isPaused = isPaused,
                onClick = onTogglePause,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Spacing.sm),
            )
        }
    }
}

@Composable
private fun PlaybackError(message: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = Spacing.sm)) {
            Text("重试")
        }
    }
}

@Composable
private fun PlayButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = PhosphorIcons.Regular.Play,
            contentDescription = "播放",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(IconSize.lg),
        )
    }
}

@Composable
private fun PauseButton(isPaused: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = if (isPaused) PhosphorIcons.Regular.Play else PhosphorIcons.Regular.Pause,
            contentDescription = if (isPaused) "播放" else "暂停",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(IconSize.md),
        )
    }
}

/** 直播视频区与封面统一 16:9。 */
private const val VIDEO_ASPECT_RATIO = 16f / 9f
