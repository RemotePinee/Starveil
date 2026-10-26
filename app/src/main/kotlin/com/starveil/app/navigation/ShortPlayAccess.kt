package com.starveil.app.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starveil.app.AppContainer
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.TrySeeGate
import com.starveil.feature.player.VERTICAL_PLAY_SOURCE_INDEX
import com.starveil.feature.player.VerticalPlayViewModel
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal data class ShortPlayAccess(
    val vodId: Int,
    val episodeNumber: Int,
    val refresh: Int,
    val active: Boolean,
)

/** 权限按集、会话及真实前台周期隔离；手势与抽屉的短暂 inactive 只暂停请求和播放。 */
@Composable
internal fun rememberShortPlayGate(
    container: AppContainer,
    viewModel: VerticalPlayViewModel,
    request: ShortPlayAccess,
    playbackToken: Any = viewModel.defaultPlaybackToken,
): AppResult<TrySeeGate>? {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val foreground = rememberShortPlayForegroundCycle(lifecycle)
    val foregroundCycle by foreground
    val session by container.session.collectAsStateWithLifecycle()
    var result by remember(
        request.vodId, request.episodeNumber, request.refresh, session, foregroundCycle, playbackToken,
    ) {
        mutableStateOf<AppResult<TrySeeGate>?>(null)
    }
    LaunchedEffect(request, foregroundCycle, session, playbackToken) {
        val canRequest = request.active && foregroundCycle != null && viewModel.ownsPlayback(playbackToken)
        if (!canRequest || result != null) return@LaunchedEffect
        val response = container.accessRepository.checkTrySee(
            vodId = request.vodId,
            episodeNumber = request.episodeNumber,
            sourceIndex = VERTICAL_PLAY_SOURCE_INDEX,
        )
        coroutineContext.ensureActive()
        if (!viewModel.acceptsShortPlayGate(request, playbackToken)) return@LaunchedEffect
        if (!lifecycle.isCurrentGateCycle(foreground, foregroundCycle)) return@LaunchedEffect
        if (response is AppResult.Success) viewModel.updateTrySee(request.episodeNumber, response.value, playbackToken)
        result = response
    }
    return result
}

private fun VerticalPlayViewModel.acceptsShortPlayGate(request: ShortPlayAccess, token: Any): Boolean =
    ownsPlayback(token) && state.value.current?.number == request.episodeNumber

private fun Lifecycle.isCurrentGateCycle(foreground: State<Any?>, cycle: Any?): Boolean =
    currentState == Lifecycle.State.RESUMED && foreground.value === cycle

/** 每次真实恢复都换身份，即使暂停与恢复之间没有重组，也不能沿用上次权限。 */
@Composable
private fun rememberShortPlayForegroundCycle(lifecycle: Lifecycle): State<Any?> {
    val cycle = remember(lifecycle) { mutableStateOf<Any?>(null) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> cycle.value = Any()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> cycle.value = null
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return cycle
}

/** 查询失败停播并提供重试，不把断网当作免费权限。 */
@Composable
internal fun ShortPlayGateNotice(result: AppResult<TrySeeGate>?, onRetry: () -> Unit) {
    // 等待态交给播放器同一中心槽绘制，权限刷新只停播，不再用全屏黑底抹掉已播帧。
    if (result !is AppResult.Failure) return
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.pageHorizontal),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            val error = result.error
            val message = (error as? AppError.Business)?.message?.takeIf { it.isNotBlank() }
                ?: "播放权限查询失败，请重试"
            Text(message, color = starveilExtraColors.onImage)
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}
