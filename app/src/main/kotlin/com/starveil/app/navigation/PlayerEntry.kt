package com.starveil.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.starveil.app.AppContainer
import com.starveil.app.ads.RewardRequestContext
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.common.ad.AdSlot
import com.starveil.core.designsystem.component.LocalRewardAd
import com.starveil.core.model.Vod
import com.starveil.feature.player.PlayerEvent
import com.starveil.feature.player.PlayerRoute
import com.starveil.feature.player.UnlockOptions
import com.starveil.feature.player.unlockOptions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import com.starveil.feature.player.PlayerViewModel

/**
 * 独立播放页装配：先取详情，再交给 [rememberGate] / [rememberPlayerViewModel]。
 *
 * 详情页的内嵌播放器（[InlineDetailPlayer]）复用同一套闸门与 VM 构建逻辑，
 * 差别只在渲染形态：这里铺满整屏，那里是 16:9 半屏。
 */
@Composable
fun PlayerEntry(
    container: AppContainer,
    vodId: Int,
    sourceIndex: Int,
    episodeIndex: Int,
    modifier: Modifier = Modifier,
    onEvent: (PlayerEvent) -> Unit = {},
    onPurchase: (vodId: Int, episodeNumber: Int, sourceIndex: Int, sid: Int) -> Unit =
        { _, _, _, _ -> },
    onFullscreenChange: (Boolean) -> Unit = {},
) {
    // 详情 + 断点一起取：从观看记录进来时按服务端进度续播（同源同集才续）
    val loaded by produceState<Pair<AppResult<Vod>, Long>?>(initialValue = null, vodId) {
        val detail = container.vodRepository.detail(vodId)
        // 精确断点：v1.vod/videoProgress（cuican_vlog），不是最近 10 条的列表
        val stored = (container.playbackRepository.progressOf(vodId) as? AppResult.Success)?.value
        val sameEpisode = stored != null &&
            stored.sourceIndex == sourceIndex &&
            stored.episodeIndex == episodeIndex &&
            stored.resumable
        value = detail to if (sameEpisode) stored.positionSec * MILLIS_PER_SECOND else 0L
    }

    when (val current = loaded?.first) {
        null -> PlayerMessage(modifier, loading = true, message = "")
        is AppResult.Failure -> PlayerMessage(
            modifier = modifier,
            loading = false,
            message = current.error.displayMessage(),
        )

        is AppResult.Success -> PlayerReady(
            container = container,
            request = PlayerRequest(
                vod = current.value,
                sourceIndex = sourceIndex,
                episodeIndex = episodeIndex,
                resumeMs = loaded?.second ?: 0L,
            ),
            callbacks = PlayerCallbacks(
                onEvent = onEvent,
                onPurchase = onPurchase,
                onFullscreenChange = onFullscreenChange,
            ),
            modifier = modifier,
        )
    }
}

/** 秒 → 毫秒。 */
private const val MILLIS_PER_SECOND = 1_000L

/** 播放页回调：事件与购买。同样是为了参数个数上限。 */
private data class PlayerCallbacks(
    val onEvent: (PlayerEvent) -> Unit,
    val onPurchase: (vodId: Int, episodeNumber: Int, sourceIndex: Int, sid: Int) -> Unit,
    val onFullscreenChange: (Boolean) -> Unit,
)

@Composable
private fun PlayerReady(
    container: AppContainer,
    request: PlayerRequest,
    callbacks: PlayerCallbacks,
    modifier: Modifier,
) {
    when (val load = rememberGate(container, request)) {
        GateLoad.Loading -> PlayerMessage(modifier, loading = true, message = "")
        is GateLoad.Ready -> PlayerLoaded(
            container = container,
            load = load,
            callbacks = callbacks,
            modifier = modifier,
        )
    }
}

/**
 * 回到前台时重查一次试看闸门。
 *
 * 第一次 resume 用的是构造期已经查过的结果，不重复请求；之后每次 resume（支付页返回、
 * 从后台切回来）都查一次并用 [PlayerViewModel.updateAccess] 推给播放器。
 */
@Composable
private fun RecheckGateOnResume(
    container: AppContainer,
    request: PlayerRequest,
    viewModel: PlayerViewModel,
) {
    val scope = rememberCoroutineScope()
    var resumedOnce by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit, viewModel) {
        if (resumedOnce) {
            scope.launch {
                // 播放器内换过集时，要按"现在这一集"查，不能拿进入页面时的集号
                val current = viewModel.state.value
                val result = container.accessRepository.checkTrySee(
                    vodId = request.vod.id,
                    episodeNumber = episodeNumberOf(request.vod, current.sourceIndex, current.episodeIndex),
                    sourceIndex = current.sourceIndex,
                )
                (result as? AppResult.Success)?.value?.let { gate ->
                    viewModel.updateAccess(gate.access, gate.trySeeSeconds)
                }
            }
        } else {
            resumedOnce = true
        }
        onPauseOrDispose { }
    }
}

@Composable
private fun PlayerLoaded(
    container: AppContainer,
    load: GateLoad.Ready,
    callbacks: PlayerCallbacks,
    modifier: Modifier,
) {
    val viewModel = rememberPlayerViewModel(container, load)
    val request = load.request
    val context = LocalContext.current
    // 独立播放页一进来就是全屏（横屏）：从观看记录直接点进播放时，竖屏只露一条画面，
    // 观感像"点错了页面"。内嵌在详情页的半屏播放器不走这里。
    LaunchedEffect(viewModel) { viewModel.onFullscreenToggle() }
    // 从支付页/积分买断回来时重查试看权限：构造期注入的 gate 已经过期了，
    // 不重查的话用户付了钱还停在"本集需单独购买"。
    RecheckGateOnResume(container = container, request = request, viewModel = viewModel)
    val refreshGate = rememberGateRefresh(container, request, viewModel)
    val showRewardAd = LocalRewardAd.current
    val adScope = rememberCoroutineScope()
    PlayerRoute(
        viewModel = viewModel,
        modifier = modifier,
        // 能力位来自服务端 checkVodTrySee：会员关闭时 canVip=0，浮层只剩积分（与广告）。
        // 广告还要叠加网关 ready——后台配了素材才为真。
        unlock = load.gate?.unlockOptions(container.adGateway.isReady(AdSlot.REWARD))
            ?: UnlockOptions(),
        // 看完广告后重查闸门，由服务端结论决定是否放行；客户端不自行改 access。
        // 请求广告前先声明"这一集是谁在看"：trans_id 只由平台在回调里发给服务端，
        // 客户端能做的就是把身份带上，再看服务端有没有因此写入解锁记录。
        onWatchAd = {
            val current = viewModel.state.value
            val episode = episodeNumberOf(request.vod, current.sourceIndex, current.episodeIndex)
            RewardRequestContext.begin(container.sessionSnapshot()?.userId, request.vod.id, episode)
            showRewardAd {
                RewardRequestContext.clear()
                adScope.launch { refreshGate() }
            }
        },

        onEvent = { event ->
            if (event is PlayerEvent.RequestPurchase) {
                callbacks.onPurchase(
                    request.vod.id,
                    request.episodeNumber,
                    request.sourceIndex,
                    // 真 sid：付费必须打到用户正在看的那一源
                    sourceSidOf(request.vod, request.sourceIndex),
                )
            } else {
                callbacks.onEvent(event)
            }
        },
        onFullscreenChange = { fullscreen -> context.applyPlayerFullscreen(fullscreen) },
    )
}

@Composable
private fun PlayerMessage(modifier: Modifier, loading: Boolean, message: String) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (loading) {
            CircularProgressIndicator()
        } else {
            Text(text = message)
        }
    }
}

private fun AppError.displayMessage(): String = when (this) {
    is AppError.Network -> "网络异常，无法加载影片"
    is AppError.Business -> message.ifBlank { "影片不可用" }
    is AppError.Http -> "服务异常（$code）"
    is AppError.Serialization -> "数据解析失败"
    is AppError.Unknown -> "未知错误"
}
