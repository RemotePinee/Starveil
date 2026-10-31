package com.starveil.feature.player

import com.starveil.core.common.AppResult
import com.starveil.core.hongguo.HongguoConfig
import com.starveil.core.model.ParseRules
import com.starveil.core.model.PlayerInfo
import com.starveil.core.model.Vod
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 竖屏播放的装载链路：详情 -> 状态 -> 装一集。
 *
 * 单独成文件是为了让 [VerticalPlayViewModel] 不突破 200 行的类上限（AGENTS.md 第 2 节）。
 * 这里只做「仓库数据 -> UI 状态」的搬运，不持有自己的字段：可变状态仍然全部落在 VM 上，
 * 所以拆文件不会改变任何时序。
 */

/** 重拉详情：先回骨架态，网络回来再原地铺开。 */
internal fun VerticalPlayViewModel.loadDetail() {
    loadJob?.cancel()
    preloader.clear()
    val revision = stateFlow.value.playbackRevision + 1L
    stateFlow.value = stateFlow.value.metadataOnly().copy(playbackRevision = revision)
    loadJob = scope.launch {
        val result = vodRepository.detail(vodId)
        ensureActive()
        when (result) {
            is AppResult.Success -> applyVod(result.value)
            is AppResult.Failure -> stateFlow.update {
                it.copy(isLoading = false, errorMessage = verticalErrorMessage(result.error))
            }
        }
    }
}

/** 详情到手：补齐展示字段，然后把要播的那集装上。 */
internal fun VerticalPlayViewModel.applyVod(vod: Vod) {
    playerInfo = vod.playSources.getOrNull(VERTICAL_PLAY_SOURCE_INDEX)?.playerInfo
    // 竖屏短剧有自己的 ViewModel，不像长剧那样经过 PlayerViewModel，
    // 所以红果配置要在这里也写一次，否则短剧那条链路拿不到开关。
    PlayerLog.warn { PlayerLog.describePlayerInfo("vertical", playerInfo) }
    syncHongguoConfig(playerInfo)
    val previous = stateFlow.value
    val base = verticalStateOf(vod).copy(
        title = vod.name.ifBlank { previous.title },
        cover = vod.poster?.takeIf(String::isNotBlank) ?: previous.cover,
        description = vod.blurb?.takeIf(String::isNotBlank) ?: previous.description,
        category = vod.typeName?.takeIf(String::isNotBlank) ?: previous.category,
        playbackRevision = previous.playbackRevision,
    )
    if (base.episodes.isEmpty()) {
        stateFlow.value = base
        return
    }
    val start = base.episodes.indexOfFirst { it.number == initialEpisodeNumber }.coerceAtLeast(0)
    // 剧集数据一到就先把要播的那集备好：对应红果的 detail/preload，
    // 让「点开一部剧」也不用干等那 1~2 秒。
    prefetchNear(base.episodes, start, playerInfo)
    val resume = pendingResumeMs.takeIf { base.episodes[start].number == initialEpisodeNumber } ?: 0L
    playEpisode(base, start, resume)
    if (stateFlow.value.playingUrl != null) pendingResumeMs = null
}

/**
 * 装一集：预取命中直接用，未命中才归一化 → 请求头判定 → 重置进度与上报基准。
 *
 * 命中只省一次归一化，真正的收益在将来换成"已 prepared 的播放器"
 * （见 [VerticalPreloadPool] 的说明）；命中与否都不改播放器状态机。
 */
internal fun VerticalPlayViewModel.playEpisode(
    base: VerticalPlayUiState,
    index: Int,
    positionMs: Long = 0L,
) {
    val episode = base.episodes.getOrNull(index) ?: return
    val url = preloader.take(index) ?: ParseRules.normalizePlayUrl(episode.url)
    reporter.reset()
    reporter.onPlaybackStarted()
    danmuController.reset()
    stateFlow.value = base.copy(
        currentIndex = index,
        playingUrl = url,
        headers = verticalHeadersOf(url, playerInfo),
        audioUrl = null,
        positionMs = if (url != null) positionMs else 0L,
        durationMs = 0L,
        blockedAccess = null,
        isPlaying = url != null,
        isBuffering = url != null,
        errorMessage = if (url == null) MSG_NO_PLAY_URL else null,
    )
    preloader.prefetchNext(base.episodes, index)
    // 短剧是下滑连着看的，红果自己也有一发就是预取好几集（preload/v1）。
    // 材料便宜（几百字节）、头部贵（800KB），两者分开备：
    // 材料往前备 5 集，头部只备 2 集，避免预取把播放带宽吃光。
    prefetchAhead(base.episodes, index, playerInfo)
}

/** 从预览返回：把交接出去的那份状态原样装回来。 */
internal fun VerticalPlayViewModel.restorePreviewState(snapshot: VerticalPreviewSnapshot) {
    loadJob?.cancel()
    playerInfo = snapshot.playerInfo
    preloader.clear()
    trySee.gate = snapshot.gate
    trySee.episodeNumber = snapshot.gateEpisode
    stateFlow.value = snapshot.state
    reporter.reset()
    reporter.onPlaybackStarted()
    danmuController.reset()
}

/** 红果开关随片源下发，默认关；写进运行时供 DataSource 层读取。 */
private fun syncHongguoConfig(info: PlayerInfo?) {
    info?.let {
        HongguoRuntime.config = HongguoConfig.parse(
            if (it.hongguoEnabled) 1 else 0, it.hongguoMatch,
        )
    }
}

/** 要播的那集与下一集先备着：对应红果的 detail/preload。 */
private fun prefetchNear(episodes: List<VerticalEpisode>, start: Int, info: PlayerInfo?) {
    val first = episodes.getOrNull(start) ?: return
    HongguoGateDataSource.prefetch(first.url, HongguoRuntime.config, verticalHeadersOf(first.url, info))
    val next = episodes.getOrNull(start + 1) ?: return
    HongguoGateDataSource.prefetch(next.url, HongguoRuntime.config, verticalHeadersOf(next.url, info))
}

/** 往后几集：头部备 [PREFETCH_AHEAD] 集、材料备 [MATERIAL_AHEAD] 集。 */
private fun prefetchAhead(episodes: List<VerticalEpisode>, index: Int, info: PlayerInfo?) {
    for (offset in 1..PREFETCH_AHEAD) {
        val next = episodes.getOrNull(index + offset) ?: break
        HongguoGateDataSource.prefetch(next.url, HongguoRuntime.config, verticalHeadersOf(next.url, info))
    }
    for (offset in 1..MATERIAL_AHEAD) {
        val next = episodes.getOrNull(index + offset) ?: break
        HongguoGateDataSource.prefetchMaterial(
            next.url, HongguoRuntime.config, verticalHeadersOf(next.url, info),
        )
    }
}

/** 只预取下一集地址，不创建播放器；prepare 仍由 session 统一协调。 */
internal fun VerticalPreloadPool.prefetchNext(episodes: List<VerticalEpisode>, index: Int) {
    val next = episodes.getOrNull(index + 1) ?: return
    val url = ParseRules.normalizePlayUrl(next.url) ?: return
    prefetch(index + 1, url)
}

/** 预取后面几集**头部**：对应红果 preload/v1，但只备最近的，免得抢播放带宽。 */
private const val PREFETCH_AHEAD = 2

/** 预取后面几集**材料**：只要几百字节，可以备得比头部远。 */
private const val MATERIAL_AHEAD = 5
