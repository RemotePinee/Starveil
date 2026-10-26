package com.starveil.app.ads

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.starveil.core.common.AppResult
import com.starveil.core.data.AdUnlockRepository
import com.starveil.core.designsystem.component.AdBadge
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 进度上报间隔。服务端按"进度 + 真实经过时间"双条件复核，5 秒一次足够贴近。 */
private const val PROGRESS_INTERVAL_MS = 5_000L

/** 播放位置轮询间隔：只用于显示剩余秒数与判定"能不能点了"。 */
private const val POSITION_TICK_MS = 250L

private const val MILLIS_PER_SECOND = 1_000L

/** 拿不到画面尺寸时按 16:9 摆，与项目其它播放器一致。 */
private const val DEFAULT_ASPECT = 16f / 9f

private const val UNLOCK_FAILED_MESSAGE = "解锁失败，请重试"

/**
 * 直投激励视频浮层。
 *
 * 三段链路缺一不可（见 [AdUnlockRepository]）：会话由调用方领好传进来、播放期间每 5 秒
 * 报一次会话 token 对应的进度、看满 [RewardPlayback.minSeconds] 或播完才允许点「继续」→
 * 调 `complete`。**complete 返回 true 才把控制权交回 [onFinished]**，调用方随后重查闸门，
 * 客户端不写任何本地解锁标记。
 *
 * 中途关掉或播放失败只走 [onClose]：不调 complete，服务端不会有解锁记录。
 * 播放器在 `onDispose` 里 release，浮层被移出组合（含旋转、返回）都不会漏。
 */
@OptIn(UnstableApi::class)
@Composable
internal fun RewardVideoOverlay(
    playback: RewardPlayback,
    repository: AdUnlockRepository,
    onClose: () -> Unit,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember(playback.session.token) { buildRewardPlayer(context, playback.session.videoUrl) }
    var positionSec by remember { mutableIntStateOf(0) }
    var aspect by remember { mutableFloatStateOf(DEFAULT_ASPECT) }
    var ended by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) ended = true
            }

            override fun onPlayerError(error: PlaybackException) {
                failed = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        while (isActive) {
            positionSec = (player.currentPosition / MILLIS_PER_SECOND).toInt()
            delay(POSITION_TICK_MS)
        }
    }
    LaunchedEffect(player, playback.session.token) {
        // 上报失败不打断观看：服务端在 complete 时还会按自己的时钟复核一次，
        // 漏一两笔只会让服务端手里的进度偏低，不会误放行。
        while (isActive) {
            delay(PROGRESS_INTERVAL_MS)
            val position = (player.currentPosition / MILLIS_PER_SECOND).toInt()
            val started = position > 0 && !failed
            if (started && !ended) repository.reportProgress(playback.session.token, position)
        }
    }
    val canContinue = ended || positionSec >= playback.minSeconds
    RewardVideoFrame(
        player = player,
        ui = RewardVideoUi(
            aspect = aspect,
            canContinue = canContinue,
            failed = failed,
            submitting = submitting,
            message = message,
            remainingSec = (playback.minSeconds - positionSec).coerceAtLeast(0),
        ),
        onClose = onClose,
        onContinue = {
            if (!submitting) {
                submitting = true
                message = null
                scope.launch {
                    // 用此刻的真实播放位置再报一笔：5 秒节流很可能停在门槛之前，
                    // complete 是按"服务端记下的进度 ≥ seconds"校验的，少这一笔会被打回
                    val position = (player.currentPosition / MILLIS_PER_SECOND).toInt()
                    repository.reportProgress(playback.session.token, position)
                    val failure = completeOrMessage(repository, playback.session.token)
                    message = failure
                    submitting = false
                    if (failure == null) onFinished()
                }
            }
        },
    )
}

/**
 * 浮层要显示的全部状态。
 *
 * 打成一份传而不是逐个参数：Compose 函数同样受 detekt 的 5 参数上限约束。
 */
private data class RewardVideoUi(
    val aspect: Float,
    val canContinue: Boolean,
    val failed: Boolean,
    val submitting: Boolean,
    val message: String?,
    val remainingSec: Int,
)

@OptIn(UnstableApi::class)
@Composable
private fun RewardVideoFrame(
    player: Player,
    ui: RewardVideoUi,
    onClose: () -> Unit,
    onContinue: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(starveilExtraColors.scrimStrong),
    ) {
        // 全屏、居中、按原始比例完整显示（等价于 PlayerView 的 RESIZE_MODE_FIT）：
        // 不裁剪也不拉伸，多出来的区域留黑边。
        FitVideoSurface(player = player, aspect = ui.aspect, modifier = Modifier.fillMaxSize())
        AdBadge(modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(Spacing.lg))
        Text(
            text = "放弃",
            style = MaterialTheme.typography.labelLarge,
            color = starveilExtraColors.onImage,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(Spacing.lg)
                .clip(CircleShape)
                .background(starveilExtraColors.imageGlass)
                .clickable(onClick = onClose)
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        )
        RewardVideoBar(
            ui = ui,
            onClose = onClose,
            onContinue = onContinue,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** 底部状态与主按钮：看满之前点它不生效（激励视频不可跳过）。 */
@Composable
private fun RewardVideoBar(
    ui: RewardVideoUi,
    onClose: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = statusTextOf(ui),
            style = MaterialTheme.typography.bodyMedium,
            color = starveilExtraColors.onImage,
        )
        ui.message?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        Text(
            text = buttonTextOf(ui),
            style = MaterialTheme.typography.labelLarge,
            color = starveilExtraColors.onImage,
            modifier = Modifier
                .padding(top = Spacing.md)
                .clip(AppShape.full)
                .background(starveilExtraColors.accent)
                .clickable(enabled = ui.canContinue || ui.failed) {
                    // 失败只能走关闭；看满之前按钮形同禁用，不触发任何请求
                    if (ui.failed) onClose() else onContinue()
                }
                .padding(horizontal = Spacing.xl, vertical = Spacing.sm),
        )
    }
}

private fun statusTextOf(ui: RewardVideoUi): String = when {
    ui.failed -> "广告播放失败"
    ui.submitting -> "正在向服务端确认解锁"
    ui.canContinue -> "广告已看完"
    else -> "还需观看 ${ui.remainingSec} 秒"
}

private fun buttonTextOf(ui: RewardVideoUi): String = when {
    ui.failed -> "关闭"
    ui.submitting -> "解锁中…"
    ui.canContinue -> "继续"
    else -> "不可跳过"
}

/**
 * 按原始比例居中摆放视频画面。
 *
 * 项目其余播放器同样不用 `PlayerView`（它的 AspectRatioFrameLayout 会和容器各算一套矩形），
 * 输出绑定统一交给官方 [PlayerSurface]。
 */
@OptIn(UnstableApi::class)
@Composable
private fun FitVideoSurface(player: Player, aspect: Float, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val ratio = if (aspect.isFinite() && aspect > 0.1f) aspect else DEFAULT_ASPECT
        // 用 Dp.value 自己算：容器宽高比小于画面比例时先吃满高度，否则先吃满宽度（FIT）。
        val boxAspect = maxWidth.value / maxHeight.value
        val width = if (boxAspect.isFinite() && boxAspect > ratio) maxHeight * ratio else maxWidth
        PlayerSurface(
            player = player,
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            modifier = Modifier.width(width).height(width / ratio),
        )
    }
}

private fun buildRewardPlayer(context: Context, videoUrl: String): ExoPlayer =
    ExoPlayer.Builder(context.applicationContext).build().apply {
        setMediaItem(MediaItem.fromUri(videoUrl))
        // 激励视频只播一遍：循环会让"播完了"永远不出现，用户也就能一直看下去
        repeatMode = Player.REPEAT_MODE_OFF
        prepare()
        playWhenReady = true
    }

/**
 * 调 `complete` 并给出人类可读的失败原因；返回 null 表示服务端已写解锁记录。
 *
 * 失败一律不抛（[AdUnlockRepository] 的契约），这里只决定给用户看哪句话。
 */
private suspend fun completeOrMessage(repository: AdUnlockRepository, token: String): String? =
    when (val result = repository.complete(token)) {
        is AppResult.Success -> if (result.value) null else UNLOCK_FAILED_MESSAGE
        is AppResult.Failure -> UNLOCK_FAILED_MESSAGE
    }
