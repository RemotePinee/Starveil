package com.starveil.feature.player

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.util.UnstableApi
import androidx.compose.ui.layout.ContentScale
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW

import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.LocalSkinSelection
import com.starveil.core.designsystem.StarveilTheme
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.PlaybackPhase
import kotlinx.coroutines.delay

/** 控制层显示后无操作的停留时长。 */
private const val CHROME_IDLE_MS = 3000L

/** 缓冲多久算卡死：既没时长也没进度就一直转圈，得给用户一个重试出口。 */
private const val STALL_MS = 10_000L

@Immutable
class PlayerActions(
    val onPlayPause: () -> Unit = {},
    val onSeekTo: (Long) -> Unit = {},
    val onSpeedSelected: (Float) -> Unit = {},
    val onFullscreenToggle: () -> Unit = {},
    val onSwitchLine: () -> Unit = {},
    val onSourceSelected: (Int) -> Unit = {},
    val onEpisodeSelected: (Int) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onUpgrade: () -> Unit = {},
    val onBuy: () -> Unit = {},
    val onSendDanmu: (String, String) -> Unit = { _, _ -> },
)

/**
 * 控制层显隐。
 *
 * 纯 UI 状态，不进 ViewModel：换一下 ViewModel 状态就要存一次盘没有意义。
 * [idleKey] 给"操作一次就重置 3 秒倒计时"用。
 */
@Stable
private class ChromeVisibility {
    var visible by mutableStateOf(true)
    var idleKey by mutableIntStateOf(0)

    fun show() {
        visible = true
        idleKey++
    }

    fun toggle() {
        visible = !visible
        idleKey++
    }
}

/**
 * 播放页入口：ViewModel + ExoPlayer + 纯 UI 三者接线。
 *
 * 弹幕由 [PlayerViewModel.danmu] 提供，数据源是 :app 注入的 [DanmuSource]
 * （见 [PlayerDeps]）；这里只负责把列表交给绘制层，不碰仓库。
 */
@Composable
fun PlayerRoute(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    onEvent: (PlayerEvent) -> Unit = {},
    onFullscreenChange: (Boolean) -> Unit = {},
    unlock: UnlockOptions = UnlockOptions(),
    onWatchAd: () -> Unit = {},
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val danmu = viewModel.danmu.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val engine = remember(context) { Media3Player(context) }
    val actions = remember(viewModel, engine) { playerActions(viewModel, engine) }
    // 前台状态：退后台后播放与弹幕都要停，回来由用户自己点播放
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    LaunchedEffect(viewModel) { viewModel.ensureStarted() }
    LaunchedEffect(viewModel) { viewModel.events.collect(onEvent) }
    // 横竖屏切换属于宿主 Activity：本模块只报告状态，不直接改窗口
    LaunchedEffect(state.isFullscreen) { onFullscreenChange(state.isFullscreen) }
    DisposableEffect(engine) {
        onDispose {
            // 离页补一次位置：不补的话最后几秒会丢，观看记录断点会偏
            viewModel.persistCurrentPosition()
            engine.release()
        }
    }
    // 退到后台立刻暂停：ON_PAUSE 比 ON_STOP 早，切桌面那一刻就断，声音不会在桌面上继续响
    val playing = rememberUpdatedState(state.isPlaying)
    // 直接调 engine.pause()：不依赖状态回流的那一帧，切桌面那一刻就真的静音
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        // 幂等暂停：ON_PAUSE 与 ON_STOP 都会到，之前用 toggle 会切两次又变回播放，
        // 表现就是"退后台回来按钮还是暂停中，得点两下"
        engine.pause()
        viewModel.pausePlayback()
    }
    // 页面恢复只由官方 ContentFrame 重新绑定视频输出；不 seek，也不重建 source。
    PlayerEngineEffects(engine = engine, state = state, viewModel = viewModel)
    // 播放页固定深色：视频画面本来就是暗底，浅色皮肤下控制条、弹层、选集面板
    // 变白会直接看不见控件。皮肤只决定强调色，明暗在这里不跟系统。
    StarveilTheme(
        skinCode = LocalSkinSelection.current.effective,
        darkTheme = true,
    ) {
        PlayerScreen(
            state = state,
            engine = engine,
            actions = actions,
            danmu = danmu,
            isResumed = isResumed,
            modifier = modifier,
            unlock = unlock,
            onWatchAd = onWatchAd,
            onPauseAdHold = viewModel::onPauseAdHold,
        )
    }
}

/**
 * 纯 UI：视频面 + 弹幕 + 手势层 + 控制层 + 三态覆盖层 + 三个底部弹层。
 *
 * 播放/进度不再交给 media3 自带控制器（`useController = false`）：官方控制器的
 * 尺寸、配色与图标都跟本应用的令牌对不上，和弹幕层、顶栏放一起像两套软件拼起来。
 *
 * 弹幕弹层与选集/倍速共用 [PlayerSheetHost]：弹层是 Activity 级窗口，内嵌在详情页
 * 的 16:9 小窗里也不会被压成一条。
 */
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    engine: Media3Player,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
    danmu: List<DanmuItem> = emptyList(),
    /** 页面是否在前台（RESUMED）：退后台时播放停、弹幕也必须停，否则黑着屏还在飘字。 */
    isResumed: Boolean = true,
    unlock: UnlockOptions = UnlockOptions(),
    onWatchAd: () -> Unit = {},
    /** 贴片占屏时置 true：ViewModel 据此拦住"恢复播放"，倒计时才真的生效。 */
    onPauseAdHold: (Boolean) -> Unit = {},
) {
    var sheet by remember { mutableStateOf<PlayerSheetKind?>(null) }
    // 弹幕显示偏好（开关/字号/颜色/行数/区域/不透明度）收在 DanmuPrefs：
    // 行数与区域有约束关系，也让本函数的圈复杂度不超 detekt 上限
    val danmuPrefs = rememberDanmuPrefs()
    val chrome = remember { ChromeVisibility() }
    // 横滑预览位置：不为空时进度条画这个值，松手后清空回到真实播放位置
    var seekPreview by remember { mutableStateOf<Long?>(null) }
    // 播放中常亮：横屏看片与内嵌小窗都会在系统休眠时限里黑屏。
    // 传 isPlaying 而不是恒为 true：内嵌在详情页时暂停就该让系统正常息屏。
    KeepScreenOnWhilePlaying(enabled = state.isPlaying)
    // 窗口亮度记在页面这一层：GestureLevels 构造时抓进入前的原始值，
    // 页面销毁时按它还原——否则退出播放页后，详情页/首页会保留调过的亮度。
    val context = LocalContext.current
    val levels = remember(context) { GestureLevels(context) }
    DisposableEffect(levels) { onDispose { levels.restoreBrightness() } }
    // 回前台只做兜底恢复，不重建 SurfaceView：重建会把正在拉的流打断，表现成"一直转圈"
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val position = rememberUpdatedState(state.positionMs)
    DisposableEffect(lifecycle, engine) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                // 出错（多半是签名地址过期）就重新解析；只是掉了 source 才就地重建
                if (engine.hasError) actions.onRetry() else engine.recoverIfNeeded(position.value)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val stalled = rememberStalled(isBuffering = state.isBuffering, durationMs = state.durationMs)
    // 弹层打开时控制层保持可见，免得"点开面板，控制层先自己消失了"
    val chromeShown = chromeShownOf(chrome.visible, sheet)
    // 关掉开关只是不渲染这一层，时间轴与拉取仍在跑，重新打开不用等下一个窗口；
    // 解析阶段也不渲染：画面还没出来弹幕先飘，会盖在转圈/海报上
    val danmuItems = visibleDanmu(danmuPrefs.enabled, state, danmu)
    ChromeIdleEffect(playing = state.isPlaying, shown = chromeShown, idleKey = chrome.idleKey) {
        chrome.visible = false
    }
    // 返回键优先级：先关弹层 → 再退全屏 → 最后才退出播放页。
    // 少了"退全屏"这一级，全屏按返回会直接把播放页弹出栈：先看到首页再转回竖屏，就是那一下闪
    BackHandler(enabled = sheet != null) { sheet = null }
    BackHandler(enabled = sheet == null && state.isFullscreen) { actions.onFullscreenToggle() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(starveilExtraColors.scrimStrong),
    ) {
        // 视频面永远铺满自己的容器，画面比例由 VideoSurface 的 aspectRatio 兜住
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        // 布局与系统栏按"系统真的转完屏"之后的状态走：退出全屏时窗口还有 200-400ms
        // 停在横屏，这期间若先按竖屏排一遍，视频会先在横向窗口里被拉伸一下再跳回正常比例。
        val settledFullscreen = rememberSettledFullscreen(state.isFullscreen, landscape)
        val immersive = isImmersive(settledFullscreen, landscape)
        VideoSurface(engine = engine, modifier = Modifier.fillMaxSize())
        DanmuLayer(
            items = danmuItems,
            // 不能因为弹层打开就停绘制：停掉后位置时钟冻结、每 500ms 被状态拉回去，
            // 弹幕就变成"一下一下跳"。这里只跟着播放状态走。
            // 前台 + 正在播才推进弹幕：退后台播放停了，弹幕也必须停
            isPlaying = state.isPlaying && isResumed,
            fontScale = danmuPrefs.fontScale,
            positionMs = state.positionMs,
            // 行数必须显式传：漏了就一直按默认 3 行画，用户改行数等于没改
            laneCount = danmuPrefs.laneCount,
            opacity = danmuPrefs.opacity,
            areaFraction = danmuPrefs.areaFraction,
            modifier = Modifier.fillMaxSize().padding(top = Spacing.xs),
        )
        PlayerGestureLayer(
            state = state,
            enabled = state.phase is PlaybackPhase.Playing && !state.isBlocked,
            callbacks = gestureCallbacks(actions = actions, chrome = chrome, onPreview = { seekPreview = it }),
            levels = levels,
            modifier = Modifier.fillMaxSize(),
        )
        // 暂停贴片：位于手势层之上、三态覆盖层与控制层之下。它不跟随控制层显隐——
        // 暂停态点屏幕只隐藏播控，广告留在画面上（见 PlayerPauseAd 的 visibility 说明）
        PlayerPauseAdLayer(
            state = state,
            onHold = onPauseAdHold,
            modifier = Modifier.fillMaxSize(),
        )
        PlayerOverlay(
            state = state,
            actions = actions,
            modifier = Modifier.fillMaxSize(),
            unlock = unlock,
            onWatchAd = onWatchAd,
        )
        if (stalled) {
            StalledHint(onRetry = actions.onRetry, modifier = Modifier.align(Alignment.Center))
        }
        if (!state.isBlocked) {
            CompositionLocalProvider(LocalSeekPreview provides seekPreview) {
                PlayerChromeOverlay(
                    state = state,
                    actions = actions,
                    config = ChromeConfig(
                        visible = chromeShown,
                        danmuEnabled = danmuPrefs.enabled,
                        immersive = immersive,
                        onInteract = chrome::show,
                        onToggleDanmu = { danmuPrefs.enabled = !danmuPrefs.enabled },
                        onOpenSheet = { sheet = it },
                        // 倍速不再开弹层：点一下切下一档，按钮上直接显示当前档位
                        onCycleSpeed = { actions.onSpeedSelected(PlayerSpeeds.next(state.speed)) },
                    ),
                )
            }
        }
        // 弹层在这里调用：它要读 immersive 决定弹层窗口要不要隐藏系统栏
        PlayerSheetHost(
            kind = sheet,
            state = state,
            actions = actions,
            onDismiss = { sheet = null },
            immersive = immersive,
            danmu = DanmuSettings(style = danmuPrefs.style(), events = danmuPrefs.events()),
        )
    }
}

private fun gestureCallbacks(
    actions: PlayerActions,
    chrome: ChromeVisibility,
    onPreview: (Long?) -> Unit,
): GestureCallbacks =
    GestureCallbacks(
        onTap = chrome::toggle,
        onDoubleTap = {
            chrome.show()
            actions.onPlayPause()
        },
        onSeek = actions.onSeekTo,
        onInteraction = chrome::show,
        onSeekPreview = onPreview,
    )

/**
 * 缓冲卡死检测：既没时长也没进度，10 秒后返回 true。
 *
 * 不在这里自动重开线路——重开是用户决定的事，这里只负责把"一直转圈"变成一个有出口的状态。
 */
@Composable
private fun rememberStalled(isBuffering: Boolean, durationMs: Long): Boolean {
    var stalled by remember { mutableStateOf(false) }
    LaunchedEffect(isBuffering, durationMs) {
        stalled = false
        if (isBuffering && durationMs == 0L) {
            delay(STALL_MS)
            stalled = true
        }
    }
    return stalled
}

/** 播放中 3 秒无操作就收起控制层；暂停或控制层已隐藏时不再计时。 */
@Composable
private fun ChromeIdleEffect(playing: Boolean, shown: Boolean, idleKey: Int, onIdle: () -> Unit) {
    val idle = rememberUpdatedState(onIdle)
    LaunchedEffect(playing, shown, idleKey) {
        if (shown && playing) {
            delay(CHROME_IDLE_MS)
            idle.value()
        }
    }
}

/** 弹幕可见性：开关关掉、试看拦截、缓冲或出错时不画；时间轴照常在跑。 */
private fun visibleDanmu(
    enabled: Boolean,
    state: PlayerUiState,
    items: List<DanmuItem>,
): List<DanmuItem> = if (drawableDanmu(enabled, state)) items else emptyList()

private fun drawableDanmu(enabled: Boolean, state: PlayerUiState): Boolean =
    enabled && !blockingForDanmu(state) && state.phase is PlaybackPhase.Playing

/** 这些状态下不该出现弹幕：试看拦截、缓冲中、或已经有错误覆盖层。 */
private fun blockingForDanmu(state: PlayerUiState): Boolean =
    state.isBlocked || state.isBuffering || state.errorMessage != null

/**
 * 视频面（长剧与短剧共用同一条纹理路径）。
 *
 * 不用 `PlayerView`：它内部的 `AspectRatioFrameLayout` 会和我们的容器各算一套画面矩形，
 * 容器与窗口不完全重合时就会偏心（典型的"上下左留黑、右边贴边"）。
 *
 * 直接使用 Media3 原生 Player 与 ContentFrame：Surface 绑定、解绑和释放都由 Media3
 * 管理。页面组合重建时不接管 TextureView listener，也不跨 View 保存 SurfaceTexture。
 */
@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(engine: Media3Player, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        ContentFrame(
            player = engine.mediaPlayer,
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            contentScale = ContentScale.Fit,
            keepContentOnReset = true,
            modifier = Modifier.aspectRatio(engine.videoAspectRatio),
            shutter = {},
        )
    }
}

/** 弹层打开时控制层保持可见；抽成纯函数，避免主 Composable 的圈复杂度继续上涨。 */
private fun chromeShownOf(visible: Boolean, sheet: PlayerSheetKind?): Boolean =
    visible && sheet == null

/** 全屏且横屏才算沉浸：竖屏全屏不收起系统栏，避免画面被拉成两截。 */
private fun isImmersive(fullscreen: Boolean, landscape: Boolean): Boolean =
    fullscreen && landscape

/** 退出全屏后等系统转回竖屏再切布局的宽限；设备锁竖屏时靠它兜底，不能永远等。 */
private const val FULLSCREEN_SETTLE_GRACE_MS = 600L

/**
 * "已经生效的全屏态"。
 *
 * 进入全屏立刻算全屏（系统马上开始转屏，先按全屏排不会错）；
 * 退出时只在**真的变回竖屏**（或超过宽限）之后才算退出——否则会出现
 * "点返回先被拉成全屏再跳回竖屏"的抖动。
 */
@Composable
public fun rememberSettledFullscreen(fullscreen: Boolean, landscape: Boolean): Boolean {
    var settled by remember { mutableStateOf(fullscreen) }
    LaunchedEffect(fullscreen, landscape) {
        if (fullscreen) {
            settled = true
            return@LaunchedEffect
        }
        if (!landscape) {
            settled = false
            return@LaunchedEffect
        }
        delay(FULLSCREEN_SETTLE_GRACE_MS)
        settled = false
    }
    return settled
}

/**
 * 播放中常亮，离页还原。
 *
 * 用 View 的 `keepScreenOn` 而不是给窗口加 `FLAG_KEEP_SCREEN_ON`：内嵌播放与详情页
 * 共用同一个 Window，加窗口 flag 会把整页都锁住；View 级标志只作用于本页这层 View，
 * 且能在 onDispose 里按原值还原（同 `VerticalPlayDestination.KeepScreenOn` 的做法）。
 */
@Composable
private fun KeepScreenOnWhilePlaying(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val previous = view.keepScreenOn
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = previous }
    }
}
