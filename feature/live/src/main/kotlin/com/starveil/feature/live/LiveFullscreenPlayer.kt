package com.starveil.feature.live

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.common.liveErrorMessage
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.LiveChannel
import kotlinx.coroutines.delay

/**
 * 触发换台的横向拖动距离：按屏幕宽的 18% 算，不再用固定 px。
 *
 * 固定 320px 在低分辨率设备上要滑掉四分之一屏才认，手感就是"滑了没反应"。
 */
private const val SWIPE_RATIO = 0.18f

/** 切台遮罩淡出时长：新源出帧后画面淡进来，不硬切。 */
private const val CURTAIN_FADE_MS = 180

/**
 * 全屏直播播放器。
 *
 * 原来是页头下方一个 16:9 的小窗，既不能全屏也没有换台——直播这种场景
 * 用户基本都要全屏看，所以改成点频道直接进全屏。
 *
 * 换台用**同一个 ExoPlayer 换 MediaItem**，不是每个频道起一个实例：
 * 一个 ExoPlayer 自带缓冲与解码器，几秒内切十几个台也不会有资源泄漏。
 * 左右滑动或点底部箭头都能切，切台时复用同一块 TextureView。
 */
@OptIn(UnstableApi::class)
@Composable
internal fun LiveFullscreenPlayer(
    model: LivePickerModel,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onSelectGroup: (String) -> Unit,
) {
    val channels = model.entries.map { it.channel }
    if (channels.isEmpty()) {
        return
    }
    val context = LocalContext.current
    // 播的是哪一台，用 channel.id 记身份，不用下标：换分类是原地替换 items
    // （LiveViewModel.selectGroup → load → fetchFirst），条数会变少——在"全部"里看第 40 台
    // 时切到只有 12 台的组，旧下标直接越界；不越界时也可能指着另一台，顶栏与画面就各说各话。
    var selectedId by remember {
        mutableLongStateOf(channels[initialIndex.coerceIn(0, channels.lastIndex)].id)
    }
    // 手势回调在 pointerInput 里长期存活，用 state 读最新列表，避免抓住旧分类的副本
    val liveChannels = rememberUpdatedState(channels)
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    // 新源真正出帧前，用一块黑幕把上一台的最后一帧盖住：
    // TextureView 会一直保留旧画面，不盖就是"卡在上一个台 + 转圈"。
    var surfaceReady by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    // 视频原始宽高比：视频面铺满整屏会把画面拉变形（4:3 的台被拉成 21:9），
    // 所以按 ExoPlayer 报的 size 动态约束，多出来的地方留黑边。
    var videoAspect by remember { mutableFloatStateOf(DEFAULT_ASPECT) }
    // 换台提示只在进入时出现一次，2.5 秒后淡出，不常驻挡画面
    var showHint by remember { mutableStateOf(true) }
    var showPicker by remember { mutableStateOf(false) }
    // 每次组合按 id 反查：该台不在新分类里就退回第一台，下标永远落在合法范围
    val index = channelIndex(channels, selectedId)
    val channel = channels[index]

    LaunchedEffect(Unit) {
        delay(HINT_VISIBLE_MS)
        showHint = false
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }
    // 直播全屏必须横屏：竖屏全屏时 16:9 的画面要么上下大片黑边、要么被裁掉一半。
    //
    // 只改**当前 Activity 的 requestedOrientation**，不碰系统"自动旋转"开关
    // （那是 Settings.System.ACCELEROMETER_ROTATION，改它要 WRITE_SETTINGS 权限，
    // 项目没申请也没调用）。Activity 销毁后这个值随之失效，不影响别的应用。
    //
    // 退出时**强制回竖屏**而不是恢复进入前的值：进入前多半是 UNSPECIFIED，
    // 恢复它等于交还给传感器——手机还横着拿就仍是横屏，看着就像"锁方向被解开了"。
    // 点播播放器（`PlayerHost.applyPlayerFullscreen`）也是这么处理的。
    val activity = context.findActivity()
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // 只有转横屏不叫全屏：状态栏还在的话视频顶部会被时间和电量压住
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    // 换台：只换 MediaItem，播放器实例不动。
    // 直播地址基本都是 m3u8，统一用 HlsMediaSource；非 HLS 的 flv/直链由 ExoPlayer 自动探测。
    LaunchedEffect(channel.id, channel.url) {
        val url = channel.url
        // 先把旧画面盖掉再装载：否则切到一个卡住的台时，观众看到的是上一台
        // 的画面配一个转圈，会以为"没切过去"
        surfaceReady = false
        if (url.isNullOrBlank()) {
            error = "这个频道没有播放地址"
            return@LaunchedEffect
        }
        error = null
        loading = true
        val source = HlsMediaSource.Factory(DefaultHttpDataSource.Factory())
            .setAllowChunklessPreparation(true)
            .createMediaSource(MediaItem.fromUri(Uri.parse(url)))
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                loading = state == Player.STATE_BUFFERING
                // READY 才是"新源已经有画面了"：这时才让黑幕淡出
                if (state == Player.STATE_READY) {
                    surfaceReady = true
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                loading = false
                // 不再把 ERROR_CODE_* 抛给用户：403 说被限制、404 说地址失效、
                // 连不上说网络，用户至少知道下一步该做什么
                error = liveErrorMessage(e.errorCodeName, httpStatusOf(e))
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                // 按真实画面比例收缩视频面，别把 4:3 的台拉成整屏
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        LiveDialogImmersive()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            // 按视频原始比例居中：多余区域留黑边，不做任何拉伸。
            // matchHeightConstraintsFirst=true 让它在横屏下先吃满高度，再按比例算宽度。
            PlayerSurface(
                player = player,
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                modifier = Modifier
                    .align(Alignment.Center)
                    .aspectRatio(videoAspect, matchHeightConstraintsFirst = true),
            )

            SwitchCurtain(visible = !surfaceReady)

            // 左右滑动换台：松手时才判定，拖动过程中只累积距离
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(channels.size) {
                        detectHorizontalDragGestures(
                            // 判定单独抽成 swipedIndex()：条件留在 lambda 里会让主函数
                            // 的圈复杂度顶到阈值（detekt CyclomaticComplexMethod = 10）
                            onDragEnd = {
                                val list = liveChannels.value
                                val next = swipedIndex(
                                    channelIndex(list, selectedId),
                                    drag,
                                    list.lastIndex,
                                    size.width * SWIPE_RATIO,
                                )
                                selectedId = list[next].id
                                drag = 0f
                            },
                            onDragCancel = { drag = 0f },
                        ) { _, amount -> drag += amount }
                    },
            )

            LivePlayerTopBar(
                name = channel.name,
                position = "${index + 1} / ${channels.size}",
                onDismiss = onDismiss,
                onOpenPicker = { showPicker = true },
            )
            LivePlayerStatus(loading = loading, error = error)
            LivePlayerHint(visible = showHint)
            if (showPicker) {
                LiveChannelPicker(
                    model = model,
                    onPick = { picked ->
                        // 面板给的是当前列表的下标；换分类的瞬间列表可能已换代，越界就当没点
                        channels.getOrNull(picked)?.let { selectedId = it.id }
                        showPicker = false
                    },
                    onDismiss = { showPicker = false },
                    onSelectGroup = { name ->
                        showPicker = false
                        onSelectGroup(name)
                    },
                )
            }
        }
    }
}

/**
 * 切台/首次装载时的黑幕。
 *
 * TextureView 在换源时不会自己清屏，上一台的最后一帧会一直挂在那儿；切到一个
 * 卡住的台，观众看到的就是"上一台的画面 + 转圈"。这块黑幕铺满整屏，新源 READY
 * 后淡出而不是直接消失，切换过程就不会硬切。
 */
@Composable
private fun SwitchCurtain(visible: Boolean) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = CURTAIN_FADE_MS),
        label = "liveSwitchCurtain",
    )
    if (alpha <= 0.01f) {
        return
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(alpha)
            .background(Color.Black),
    )
}

/** 按 id 在列表里定位；找不到（换分类后这台不在新列表里）退回第一台。 */
private fun channelIndex(channels: List<LiveChannel>, id: Long): Int =
    channels.indexOfFirst { it.id == id }.coerceAtLeast(0)

private fun swipedIndex(current: Int, drag: Float, lastIndex: Int, threshold: Float): Int = when {
    drag < -threshold && current < lastIndex -> current + 1
    drag > threshold && current > 0 -> current - 1
    else -> current
}
/**
 * 从 Compose 的 Context 里找宿主 Activity。
 *
 * Dialog 拿到的 `LocalContext` 往往是 `ContextWrapper` 链（不是 Activity 本身），
 * 改方向、改亮度这类窗口级操作都得先拆到真正的 Activity。
 */
private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) {
            return context
        }
        context = context.baseContext
    }
    return null
}

/** 进入全屏时的一次性提示：淡入停留几秒后淡出，不常驻挡画面。 */

/** 默认按 16:9 摆位，等 ExoPlayer 报出真实尺寸后再校正。 */
private const val DEFAULT_ASPECT = 16f / 9f

/** 换台提示停留时长：够看清，又不至于一直挡画面。 */
private const val HINT_VISIBLE_MS = 2_500L

/**
 * Dialog 这个 overlay 窗口自己的系统栏沉浸。
 *
 * 全屏时 `activity.window` 上的 hide 管不到 Dialog——它有独立的 Window，
 * 状态栏会一直压在最上面（播放器顶部被时间和电量盖住）。
 * 通过 `LocalView` 拿到 Dialog 的 window，再对它做同一套隐藏。
 */
@Composable
private fun LiveDialogImmersive() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
