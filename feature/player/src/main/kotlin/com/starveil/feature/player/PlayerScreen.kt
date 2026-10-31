package com.starveil.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState

import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.LocalSkinSelection
import com.starveil.core.designsystem.StarveilTheme
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
internal class ChromeVisibility {
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
    /**
     * 外部已经建好的播放器。内嵌播放器在探测地址阶段就把它建起来，省掉
     * 「探测完 → 再建播放器」那几百毫秒的串行等待；独立播放页不传，这里自己建。
     *
     * **所有权**：谁传谁释放。传进来的实例这里不 release，否则外部还在用就被销毁了。
     */
    engine: Media3Player? = null,
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val danmu = viewModel.danmu.collectAsStateWithLifecycle().value
    // 播放位置独立于 UiState：每 500ms 变一次的值不能挂在整页状态上。
    // 这里拿到的只是 State 对象本身，什么时候读由下层决定（见 PlayerStage）。
    val position = viewModel.position.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 只有没外部传入时才自己建：条件稳定（engine 传进来就不会变），remember 在此安全
    val ownEngine = if (engine == null) remember(context) { Media3Player(context) } else null
    val media = engine ?: ownEngine!!
    val actions = remember(viewModel, media) { playerActions(viewModel, media) }
    // 前台状态：退后台后播放与弹幕都要停，回来由用户自己点播放
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    LaunchedEffect(viewModel) { viewModel.ensureStarted() }
    // 事件闭包必须是"最新的那个"：同一个 VM 换回调时（内嵌小窗 → 独立全屏页）key 只有
    // viewModel，首帧闭包会被永久捕获，事件全发给旧接线。state 那边一直用同一招。
    val latestOnEvent = rememberUpdatedState(onEvent)
    LaunchedEffect(viewModel) { viewModel.events.collect { latestOnEvent.value(it) } }
    // 横竖屏切换属于宿主 Activity：本模块只报告状态，不直接改窗口
    LaunchedEffect(state.isFullscreen) { onFullscreenChange(state.isFullscreen) }
    DisposableEffect(media) {
        onDispose {
            // 离页补一次位置：不补的话最后几秒会丢，观看记录断点会偏
            viewModel.persistCurrentPosition()
            // 只释放自己建的；外部传入的由外部管
            ownEngine?.release()
        }
    }
    // 退到后台立刻暂停：ON_PAUSE 比 ON_STOP 早，切桌面那一刻就断，声音不会在桌面上继续响。
    // 直接调 engine.pause()：不依赖状态回流的那一帧，切桌面那一刻就真的静音
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        // 幂等暂停：ON_PAUSE 与 ON_STOP 都会到，之前用 toggle 会切两次又变回播放，
        // 表现就是"退后台回来按钮还是暂停中，得点两下"
        media.pause()
        viewModel.pausePlayback()
    }
    // 页面恢复只由官方 ContentFrame 重新绑定视频输出；不 seek，也不重建 source。
    PlayerEngineEffects(engine = media, state = state, viewModel = viewModel)
    // 播放页固定深色：视频画面本来就是暗底，浅色皮肤下控制条、弹层、选集面板
    // 变白会直接看不见控件。皮肤只决定强调色，明暗在这里不跟系统。
    StarveilTheme(
        skinCode = LocalSkinSelection.current.effective,
        darkTheme = true,
    ) {
        PlayerScreen(
            state = state,
            engine = media,
            actions = actions,
            position = position,
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
    /** 播放位置：单独一条 State，只有读它的叶子跟着刷新，整页不跟着 2Hz 重组。 */
    position: State<Long>,
    modifier: Modifier = Modifier,
    danmu: List<DanmuItem> = emptyList(),
    /** 页面是否在前台（RESUMED）：退后台时播放停、弹幕也必须停，否则黑着屏还在飘字。 */
    isResumed: Boolean = true,
    unlock: UnlockOptions = UnlockOptions(),
    onWatchAd: () -> Unit = {},
    /** 贴片占屏时置 true：ViewModel 据此拦住"恢复播放"，倒计时才真的生效。 */
    onPauseAdHold: (Boolean) -> Unit = {},
) {
    // 页面级纯 UI 状态（弹层 / 弹幕偏好 / 控制层显隐 / 横滑预览 / 亮度）收进一个持有者：
    // 都是"换一下就要存一次盘没有意义"的东西，不进 ViewModel
    val page = rememberPlayerPage()
    val immersive = rememberImmersive(state.isFullscreen)
    // 播放中常亮：横屏看片与内嵌小窗都会在系统休眠时限里黑屏。
    // 传 isPlaying 而不是恒为 true：内嵌在详情页时暂停就该让系统正常息屏。
    KeepScreenOnWhilePlaying(enabled = state.isPlaying)
    // 回前台兜底恢复（不重建 SurfaceView，重建会把正在拉的流打断）
    PlayerLifecycleEffects(engine = engine, actions = actions, position = position)
    PlayerChromeIdle(state = state, page = page)
    // 返回键优先级：先关弹层 → 再退全屏 → 最后才退出播放页。
    // 少了"退全屏"这一级，全屏按返回会直接把播放页弹出栈：先看到首页再转回竖屏，就是那一下闪
    BackHandler(enabled = page.sheet != null) { page.sheet = null }
    BackHandler(enabled = page.sheet == null && state.isFullscreen) { actions.onFullscreenToggle() }
    val stalled = rememberStalled(isBuffering = state.isBuffering, durationMs = state.durationMs)
    // 手势回调以前在包体里 new：现在只在真正影响它的输入变化时重建（见 gestureCallbacks 的注释）
    val callbacks = remember(actions, page.chrome) {
        gestureCallbacks(
            actions = actions,
            chrome = page.chrome,
            onPreview = { page.seekPreview = it },
        )
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(starveilExtraColors.scrimStrong),
    ) {
        PlayerStage(
            ui = PlayerStageUi(
                state = state,
                engine = engine,
                position = position,
                danmu = visibleDanmu(page.danmuPrefs.enabled, state, danmu),
                danmuPrefs = page.danmuPrefs,
                isResumed = isResumed,
                levels = page.levels,
            ),
            handlers = PlayerStageHandlers(
                actions = actions,
                unlock = unlock,
                onWatchAd = onWatchAd,
                onPauseAdHold = onPauseAdHold,
            ),
            callbacks = callbacks,
            stalled = stalled,
        )
        PlayerChrome(
            state = state,
            actions = actions,
            page = page,
            extras = ChromeExtras(position = position, immersive = immersive),
        )
        // 弹层在这里调用：它要读 immersive 决定弹层窗口要不要隐藏系统栏
        PlayerSheets(state = state, actions = actions, page = page, immersive = immersive)
    }
}

/**
 * 手势回调集合。
 *
 * 调用点必须 `remember(actions, chrome)` 住它：以前在 PlayerScreen 的包体里每次重组都
 * new 一份，`GestureCallbacks` 又是按字段比较的数据类，等于每次都给手势层换了新回调。
 */
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
internal fun ChromeIdleEffect(playing: Boolean, shown: Boolean, idleKey: Int, onIdle: () -> Unit) {
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

/** 弹层打开时控制层保持可见；抽成纯函数，避免主 Composable 的圈复杂度继续上涨。 */
internal fun chromeShownOf(visible: Boolean, sheet: PlayerSheetKind?): Boolean =
    visible && sheet == null

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
