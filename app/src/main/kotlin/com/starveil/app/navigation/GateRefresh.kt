package com.starveil.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.starveil.app.AppContainer
import com.starveil.core.common.AppResult
import com.starveil.feature.player.PlayerViewModel

/**
 * 手动重查试看闸门：广告看完、支付返回、积分兑换回来都走它。
 *
 * 与两个 `Recheck*GateOnResume` 的区别只是**触发时机**——那些挂在生命周期上，
 * 这个由调用方在具体动作完成后主动调；判定口径必须一致，否则会出现
 * "支付回来能看、看广告回来不能看"这类前后不一致。
 *
 * 按"现在播的这一集"查，不是进入页面时的集号：播放器内部可以换集。
 */
@Composable
internal fun rememberGateRefresh(
    container: AppContainer,
    request: PlayerRequest,
    viewModel: PlayerViewModel,
): suspend () -> Unit = remember(container, request.vod.id, viewModel) {
    {
        val current = viewModel.state.value
        val result = container.accessRepository.checkTrySee(
            vodId = request.vod.id,
            episodeNumber = episodeNumberOf(request.vod, current.sourceIndex, current.episodeIndex),
            sourceIndex = current.sourceIndex,
        )
        (result as? AppResult.Success)?.value?.let { gate ->
            viewModel.updateAccess(gate.access, gate.trySeeMinutes)
        }
    }
}
