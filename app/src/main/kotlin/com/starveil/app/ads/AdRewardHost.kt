package com.starveil.app.ads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.starveil.core.common.AppResult
import com.starveil.core.common.ad.AdGateway
import com.starveil.core.common.ad.AdOutcome
import com.starveil.core.common.ad.AdPlacement
import com.starveil.core.common.ad.AdPlacements
import com.starveil.core.common.ad.AdSlot
import com.starveil.core.data.AdUnlockRepository
import com.starveil.core.designsystem.component.LocalAdClick
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.AdSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 广告运行期依赖。
 *
 * 网关（聚合 SDK 出口）与激励解锁仓库总是一起被用到，合成一个参数传进 [AdHost]：
 * 分开写只会让宿主函数的参数个数越过 detekt 的上限，没有别的收益。
 */
internal data class AdRuntime(
    val gateway: AdGateway,
    val unlock: AdUnlockRepository,
)

/** 一次直投视频播放：服务端会话 + 服务端认定要看满的秒数。 */
internal data class RewardPlayback(
    val session: AdSession,
    val minSeconds: Int,
)

/** 正在播的那条直投视频：播放参数 + 看完之后交给谁。 */
internal data class RewardVideoShow(
    val playback: RewardPlayback,
    val onFinished: () -> Unit,
)

/**
 * 激励位的分流与状态机：视频 → 图片 → 聚合 SDK → 不弹。
 *
 * 语义与加视频之前一致：后台配了视频就必须走服务端会话（`v1.ad/session` … `v1.ad/complete`），
 * 会话开不出来也**不冒充"看完"**去走网关分支；只有完全没配直投素材时才问聚合网关。
 * 解锁放行始终只认服务端 `complete` 的返回值，这里不写任何本地标记。
 *
 * 状态放在普通类里而不是 [AdHost] 的函数体里：宿主只需要挂载一次，
 * 分支判断留在本类，宿主的分支数才不会撞上 detekt 的复杂度阈值。
 */
internal class RewardHost(
    private val ads: AdPlacements,
    private val runtime: AdRuntime,
    private val scope: CoroutineScope,
) {
    /** 非空时视频浮层盖住整页。 */
    var video by mutableStateOf<RewardVideoShow?>(null)
        private set

    /** 直投图片兜底的回调；与 [video] 互斥。 */
    var image by mutableStateOf<(() -> Unit)?>(null)
        private set

    /** 领会话的网络往返期间为真，给用户一个"点了有反应"的过渡层。 */
    var loading by mutableStateOf(false)
        private set

    /** 交给 `LocalRewardAd` 的入口。 */
    fun start(onFinished: () -> Unit) {
        when {
            ads.hasRewardVideo -> startVideo(onFinished)
            ads.reward.drawable -> image = onFinished
            runtime.gateway.isReady(AdSlot.REWARD) -> startGateway(onFinished)
            // 直投素材与 SDK 都没有：什么都不弹，解锁按钮本身也不会出现
            else -> Unit
        }
    }

    fun closeVideo() {
        video = null
    }

    fun closeImage() {
        image = null
    }

    /** 直投视频：领服务端会话，成功才出画。 */
    private fun startVideo(onFinished: () -> Unit) {
        val target = RewardRequestContext.snapshot()
        // 游客 / 入口没写目标集：服务端必然拒签，也绝不能落到网关分支冒充"看完"
        if (target == null || loading) return
        loading = true
        scope.launch {
            val session = runtime.unlock.sessionOrNull(target)
            loading = false
            if (session != null) {
                video = RewardVideoShow(playbackOf(session), onFinished)
            } else if (ads.reward.drawable) {
                // 会话开不出来（服务端拒绝 / 断网）：退回图片倒计时，别让按钮点了没反应
                image = onFinished
            }
        }
    }

    private fun startGateway(onFinished: () -> Unit) {
        scope.launch {
            if (runtime.gateway.show(AdSlot.REWARD) == AdOutcome.Completed) onFinished()
        }
    }

    private fun playbackOf(session: AdSession): RewardPlayback = RewardPlayback(
        session = session,
        // 服务端没给秒数时退回广告位配置的默认值，避免"0 秒自动完成"
        minSeconds = session.seconds.takeIf { it > 0 } ?: ads.reward.rewardSeconds,
    )
}

/** 记住一份 [RewardHost]：状态与 scope 都随宿主的组合存活。 */
@Composable
internal fun rememberRewardHost(ads: AdPlacements, runtime: AdRuntime): RewardHost {
    val scope = rememberCoroutineScope()
    return remember(ads, runtime, scope) { RewardHost(ads = ads, runtime = runtime, scope = scope) }
}

/** 挂载激励浮层。三者互斥，同一时刻最多一个非空。 */
@Composable
internal fun RewardOverlay(host: RewardHost, creative: AdPlacement, repository: AdUnlockRepository) {
    host.video?.let { show ->
        RewardVideoOverlay(
            playback = show.playback,
            repository = repository,
            onClose = host::closeVideo,
            onFinished = {
                host.closeVideo()
                show.onFinished()
            },
        )
    }
    host.image?.let { onFinished ->
        RewardImageOverlay(
            creative = creative,
            onClose = host::closeImage,
            onFinished = {
                host.closeImage()
                onFinished()
            },
        )
    }
    if (host.loading) RewardLoadingOverlay()
}

/**
 * 直投图片版激励浮层：看满 [AdPlacement.rewardSeconds] 秒才出现「继续」。
 *
 * 只在"没有配奖励视频、只配了底图"的站出现。行为与真实激励视频对齐
 * （倒计时不可跳过），但**不冒充解锁凭证**：点「继续」只是把控制权交回调用方，
 * 由它去重查闸门。
 */
@Composable
private fun RewardImageOverlay(
    creative: AdPlacement,
    onClose: () -> Unit,
    onFinished: () -> Unit,
) {
    val total = creative.rewardSeconds
    var remain by remember(creative.imageUrl) { mutableIntStateOf(total) }
    // 图没落地不扣秒：和开屏同理，加载时间不该从观看时长里扣
    var artworkReady by remember(creative.imageUrl) { mutableStateOf(false) }
    val openLanding = LocalAdClick.current
    LaunchedEffect(artworkReady, remain) {
        if (!artworkReady || remain <= 0) return@LaunchedEffect
        delay(1_000)
        remain -= 1
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(starveilExtraColors.scrimStrong),
    ) {
        AsyncImage(
            model = creative.imageUrl,
            contentDescription = creative.title.ifBlank { "激励视频" },
            contentScale = ContentScale.Crop,
            onSuccess = { artworkReady = true },
            modifier = Modifier
                .fillMaxSize()
                .clickable { if (creative.clickUrl.isNotBlank()) openLanding(creative.clickUrl) },
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxSize()
                .padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.weight(1f))
            Text(
                text = if (remain > 0) "还需观看 $remain 秒" else "广告已看完",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Text(
                text = if (remain > 0) "放弃" else "继续",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .padding(top = Spacing.md)
                    .clip(AppShape.full)
                    .background(starveilExtraColors.accent)
                    .clickable { if (remain > 0) onClose() else onFinished() }
                    .padding(horizontal = Spacing.xl, vertical = Spacing.sm),
            )
        }
    }
}

/**
 * 开会话时的过渡层。
 *
 * 点击到出画之间隔着一次网络往返，不给反馈会让用户以为按钮没生效而反复点。
 */
@Composable
private fun RewardLoadingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(starveilExtraColors.scrimStrong),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = starveilExtraColors.onImage)
            Text(
                text = "广告加载中…",
                style = MaterialTheme.typography.bodyMedium,
                color = starveilExtraColors.onImage,
                modifier = Modifier.padding(top = Spacing.md),
            )
        }
    }
}

/** 领会话失败返回 null：由调用方决定退回图片还是什么都不弹。 */
private suspend fun AdUnlockRepository.sessionOrNull(target: AdRequestContext): AdSession? =
    (startSession(target.vodId, target.episodeNumber) as? AppResult.Success)?.value
