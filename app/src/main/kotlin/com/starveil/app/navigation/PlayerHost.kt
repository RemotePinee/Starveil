package com.starveil.app.navigation

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.produceState
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.app.AppContainer
import com.starveil.core.common.AppResult
import com.starveil.core.model.TrySeeGate
import com.starveil.core.model.Vod
import com.starveil.feature.player.PlayerViewModel

/**
 * 播放请求：一部影片的某一集。收成一个对象是为了不超过 5 个参数。
 *
 * 独立播放页（[PlayerEntry]）与详情页内嵌播放器（[InlineDetailPlayer]）共用这一份装配逻辑。
 */
internal data class PlayerRequest(
    val vod: Vod,
    val sourceIndex: Int,
    val episodeIndex: Int,
    /** 断点续播位置（毫秒）；0 表示从头播。 */
    val resumeMs: Long = 0L,
) {

    /** 后端要的集数序号（nid）；取不到按第 1 集处理。 */
    val episodeNumber: Int
        get() = episodeNumberOf(vod, sourceIndex, episodeIndex)
}

/**
 * 试看闸门的加载态：Loading → Ready（gate 可能为 null，表示按免费处理）。
 *
 * [Ready] 带着它算出来的那份 [PlayerRequest]：请求变了而新闸门还在路上时，
 * 宿主可以继续用旧的一份顶着，不必把播放器整块卸载。
 */
internal sealed interface GateLoad {

    data object Loading : GateLoad

    data class Ready(val request: PlayerRequest, val gate: TrySeeGate?) : GateLoad
}

internal const val DEFAULT_EPISODE_NUMBER = 1

/** 某一集的 nid；下标越界按第 1 集处理（后端下单与试看判定只认 nid）。 */
internal fun episodeNumberOf(vod: Vod, sourceIndex: Int, episodeIndex: Int): Int =
    vod.playSources.getOrNull(sourceIndex)
        ?.episodes?.getOrNull(episodeIndex)?.episodeNumber ?: DEFAULT_EPISODE_NUMBER

/**
 * 先取详情与试看闸门，再建 [PlayerViewModel]。
 *
 * 试看参数必须在建 ViewModel 之前拿到：`PlayerDeps.trySeeAccess/trySeeMinutes`
 * 是构造期注入的，晚一步就只能在播放中途重建 VM。取不到（网络失败）按 FREE 处理，
 * 由播放页的付费入口兜底，而不是把整个页面卡在加载态。
 */
@Composable
internal fun rememberGate(container: AppContainer, request: PlayerRequest): GateLoad {
    val state by produceState<GateLoad>(
        initialValue = GateLoad.Loading,
        request.vod.id,
        request.sourceIndex,
        request.episodeIndex,
    ) {
        val result = container.accessRepository.checkTrySee(
            vodId = request.vod.id,
            episodeNumber = request.episodeNumber,
            sourceIndex = request.sourceIndex,
        )
        value = GateLoad.Ready(request, (result as? AppResult.Success)?.value)
    }
    return state
}

/**
 * 按 [GateLoad.Ready] 建播放 ViewModel。
 *
 * key 里带影片 / 源 / 集 / 权限：同一集重复进入复用同一个 VM（旋转屏幕后 `ensureStarted`
 * 不会重来），换集则换 VM；播放器实例不归 VM 管（见 [com.starveil.feature.player.PlayerRoute]
 * 里的 `remember`），所以换 VM 不会重建播放器。
 */
@Composable
internal fun rememberPlayerViewModel(container: AppContainer, load: GateLoad.Ready): PlayerViewModel {
    val request = load.request
    val gate = load.gate
    return viewModel(
        key = "player-${request.vod.id}-${request.sourceIndex}-${request.episodeIndex}-${gate?.access}",
        factory = viewModelFactory {
            initializer {
                PlayerViewModel(
                    vod = request.vod,
                    deps = container.playerDeps(gate),
                    initialSourceIndex = request.sourceIndex,
                    initialEpisodeIndex = request.episodeIndex,
                    resumePositionMs = request.resumeMs,
                )
            }
        },
    )
}

/**
 * 全屏开关 → 横竖屏 + 系统栏沉浸。Activity 的事，播放模块只报告状态
 * （PlayerRoute 的 onFullscreenChange）。
 *
 * 只有转横屏不叫全屏：状态栏和导航栏还在，视频顶部会被时间/电量压住，
 * 那排按钮就点不到了（用户截图里的原状）。所以这里同时做三件事：
 * 1. 请求横屏；2. 隐藏系统栏（下滑可临时唤出）；3. 退出时恢复系统栏 + 回竖屏。
 *
 * 独立播放页与详情页内嵌播放器共用：退出全屏统一回竖屏，避免停在横屏的详情页。
 */

/** 退出全屏后等系统栏恢复的宽限：盖住转屏动画，避免中途闪一次挤压。 */
private const val SYSTEM_BARS_RESTORE_DELAY_MS = 320L
internal fun Context.applyPlayerFullscreen(fullscreen: Boolean) {
    val activity = this as? Activity ?: return
    activity.requestedOrientation = if (fullscreen) {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    } else {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
    val window = activity.window
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    // 下滑临时唤出，几秒后自动收起，不打断播放
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    if (fullscreen) {
        controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        // 退出全屏时窗口还停在横屏，立刻恢复系统栏会在转屏中途闪一下并挤压画面；
        // 等转屏动画走完（250-350ms）再显示，观感是"一次切换"而不是两次
        window.decorView.postDelayed(
            { controller.show(WindowInsetsCompat.Type.systemBars()) },
            SYSTEM_BARS_RESTORE_DELAY_MS,
        )
    }
}
