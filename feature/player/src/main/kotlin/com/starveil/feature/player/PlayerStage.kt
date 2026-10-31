package com.starveil.feature.player

import android.content.res.Configuration
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.PlaybackPhase

/**
 * 播放面的只读输入：状态、引擎、位置、弹幕与页面级的亮度/音量读写器。
 *
 * 为什么分成 [PlayerStageUi] + [PlayerStageHandlers] 两个持有者而不是一个 11 字段的大对象：
 * detekt 的 `MatchingDeclarationName` 要求"文件里只有一个类时，类名必须等于文件名"，
 * 单类大持有者在这里既过不了那条命名检查，字段也会继续堆。按"只读输入 / 回调"拆开后，
 * 两边都只是转手给下层，不新增任何可变状态。
 */
@Immutable
internal data class PlayerStageUi(
    val state: PlayerUiState,
    val engine: Media3Player,
    /** 播放位置：单独一条 State，只有真正读它的叶子跟着刷新。 */
    val position: State<Long>,
    val danmu: List<DanmuItem>,
    val danmuPrefs: DanmuPrefs,
    val isResumed: Boolean,
    /** 页面持有的亮度/音量读写器：页面销毁时由它还原窗口亮度。 */
    val levels: GestureLevels,
)

/** 播放面的回调输入：操作集合与三态覆盖层（解锁 / 广告）的两个回调。 */
@Immutable
internal data class PlayerStageHandlers(
    val actions: PlayerActions,
    val unlock: UnlockOptions,
    val onWatchAd: () -> Unit,
    val onPauseAdHold: (Boolean) -> Unit,
)

/**
 * 播放面层叠关系（自下而上）：视频面 → 弹幕 → 手势 → 暂停贴片 → 三态覆盖层 → 缓冲超时提示。
 *
 * 写成 [BoxScope] 的扩展：这些层原本就是外层 Box 的直接子节点，[Modifier.align] 的
 * 对齐与测量顺序都要留在同一层，抽出来才不改行为。
 */
@Composable
internal fun BoxScope.PlayerStage(
    ui: PlayerStageUi,
    handlers: PlayerStageHandlers,
    callbacks: GestureCallbacks,
    stalled: Boolean,
) {
    val state = ui.state
    // 视频面永远铺满自己的容器，画面比例由 VideoSurface 的 aspectRatio 兜住
    VideoSurface(engine = ui.engine, modifier = Modifier.fillMaxSize())
    DanmuStage(
        items = ui.danmu,
        position = ui.position,
        isPlaying = state.isPlaying && ui.isResumed,
        prefs = ui.danmuPrefs,
    )
    PlayerGestureLayer(
        state = state,
        callbacks = callbacks,
        extras = GestureExtras(
            enabled = state.phase is PlaybackPhase.Playing && !state.isBlocked,
            levels = ui.levels,
            position = ui.position,
        ),
        modifier = Modifier.fillMaxSize(),
    )
    // 暂停贴片：位于手势层之上、三态覆盖层与控制层之下。它不跟随控制层显隐——
    // 暂停态点屏幕只隐藏播控，广告留在画面上（见 PlayerPauseAd 的 visibility 说明）
    PlayerPauseAdLayer(
        state = state,
        onHold = handlers.onPauseAdHold,
        modifier = Modifier.fillMaxSize(),
    )
    PlayerOverlay(
        state = state,
        actions = handlers.actions,
        modifier = Modifier.fillMaxSize(),
        unlock = handlers.unlock,
        onWatchAd = handlers.onWatchAd,
    )
    if (stalled) {
        StalledHint(onRetry = handlers.actions.onRetry, modifier = Modifier.align(Alignment.Center))
    }
}

/**
 * 弹幕层。
 *
 * 播放位置在**本组件**里读：位置每 500ms 变一次，读它就只刷新这一层，
 * 不会带着视频面、手势层、控制层一起重组（A15）。
 */
@Composable
private fun DanmuStage(
    items: List<DanmuItem>,
    position: State<Long>,
    isPlaying: Boolean,
    prefs: DanmuPrefs,
) {
    DanmuLayer(
        items = items,
        // 不能因为弹层打开就停绘制：停掉后位置时钟冻结、每 500ms 被状态拉回去，
        // 弹幕就变成"一下一下跳"。这里只跟着播放状态走。
        // 前台 + 正在播才推进弹幕：退后台播放停了，弹幕也必须停
        isPlaying = isPlaying,
        fontScale = prefs.fontScale,
        positionMs = position.value,
        // 行数必须显式传：漏了就一直按默认 3 行画，用户改行数等于没改
        laneCount = prefs.laneCount,
        opacity = prefs.opacity,
        areaFraction = prefs.areaFraction,
        modifier = Modifier.fillMaxSize().padding(top = Spacing.xs),
    )
}

/**
 * 回前台只做兜底恢复，不重建 SurfaceView：重建会把正在拉的流打断，表现成"一直转圈"。
 *
 * 位置在观察者回调里读，不在组合期读：它每次刷新都带着整页重组。
 */
@Composable
internal fun PlayerLifecycleEffects(
    engine: Media3Player,
    actions: PlayerActions,
    position: State<Long>,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, engine) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                // 出错（多半是签名地址过期）就重新解析；只是掉了 source 就就地重建
                if (engine.hasError) actions.onRetry() else engine.recoverIfNeeded(position.value)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

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

/**
 * 是否真沉浸：全屏且横屏，且按"系统真的转完屏"之后的状态算。
 *
 * 退出全屏时窗口还有 200~400ms 停在横屏，这期间若先按竖屏排一遍，视频会先在横向
 * 窗口里被拉伸一下再跳回正常比例。竖屏全屏也不收起系统栏，避免画面被拉成两截。
 */
@Composable
internal fun rememberImmersive(fullscreen: Boolean): Boolean {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    return isImmersive(rememberSettledFullscreen(fullscreen, landscape), landscape)
}

/** 全屏且横屏才算沉浸（纯函数，便于 [rememberImmersive] 与测试共用一条口径）。 */
internal fun isImmersive(fullscreen: Boolean, landscape: Boolean): Boolean = fullscreen && landscape
