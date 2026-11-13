package com.starveil.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starveil.core.data.cast.CastRepository
import com.starveil.core.model.CastUiStatus

/**
 * 投屏层的唯一入口：设备弹窗、投屏中覆盖页、全屏自动退回、连上后暂停本地。
 *
 * 单独成文件的两个原因：
 * - `PlayerScreen` 已经贴着 detekt 的圈复杂度上限，任何新的 `if` 都会把它顶红；
 * - 投屏的三种形态（弹窗 / 覆盖页 / 无）互斥，集中在一处判断比散在页面里更难写错。
 *
 * 参数打包成 5 个：超过就会踩 `LongParameterList`。
 */
@Composable
internal fun PlayerCastLayer(context: PlayerCastContext) {
    val state = context.state
    val actions = context.actions
    val cast = context.cast
    val panelOpen = context.panelOpen
    val onPanelChange = context.onPanelChange
    val target = state.castTarget?.takeIf { it.canCast }
    val session = cast
    if (target == null || session == null) return

    val status by session.status.collectAsStateWithLifecycle()
    val connected = status is CastUiStatus.Connected

    if (panelOpen && !connected) {
        CastDeviceDialog(
            cast = session,
            target = target,
            onDismiss = { onPanelChange(false) },
            onConnected = {
                onPanelChange(false)
                // 电视接管画面后本地必须停，否则手机和电视同时出声
                if (state.isPlaying) actions.onPlayPause()
            },
        )
    }

    if (connected) {
        CastNowScreen(cast = session, target = target, onRestorePosition = actions.onSeekTo)
    }

    // 全屏时投上之后立刻退回竖屏：电视已经接管画面，手机留在全屏没有意义
    LaunchedEffect(connected, state.isFullscreen) {
        if (connected && state.isFullscreen) actions.onFullscreenToggle()
    }
}

/** 投屏层入参：打包成一个持有者，避免函数签名超过 detekt 的参数阈值。 */
internal data class PlayerCastContext(
    val state: PlayerUiState,
    val actions: PlayerActions,
    val cast: CastRepository?,
    val panelOpen: Boolean,
    val onPanelChange: (Boolean) -> Unit,
)
