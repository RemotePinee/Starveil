package com.starveil.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.core.common.AppResult
import com.starveil.core.data.VodRepository
import com.starveil.core.model.DanmuTarget
import com.starveil.core.model.ParseRules
import com.starveil.core.model.PlayerInfo
import com.starveil.core.model.TrySeeGate
import com.starveil.core.model.Vod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 竖屏播放状态持有者（计划 §5 P3）。
 *
 * 数据只从 [VodRepository.detail] 来，**不新开网络路径**：短剧在后端就是
 * `mac_vod` 里的一条影片，单源多集，每集 [com.starveil.core.model.PlayUrl.url] 是直链。
 *
 * 为什么不复用 [PlayerViewModel]：
 * - 横屏页的状态机围绕"多线路探测 + 8 秒超时 + 换源"（[com.starveil.core.model.PlaybackMachine]），
 *   短剧只有一条直链，走那套状态机等于每集白等一次探测。
 * - 竖屏页的"当前集"由 pager 页号驱动，横屏页由选集弹层驱动，初始索引语义不同。
 *
 * 复用的部分：直链归一化与请求头判定走 [ParseRules]，观看时长上报走
 * [ProgressReporter] + 既有 [ProgressSink]（不新增接口），试看判定走 [TrySeeRules]。
 * 广告解锁与五态解锁状态机归计划 §5 P4，本页不做。
 */
class VerticalPlayViewModel(
    private val vodId: Int,
    private val vodRepository: VodRepository,
    private val deps: PlayerDeps,
    private val initialEpisodeNumber: Int = FIRST_EPISODE_NUMBER,
    /** 短剧默认不开弹幕（计划 §5 P3）；打开时用 [PlayerDeps.danmuSource]。 */
    private val danmuEnabled: Boolean = false,
    /** 仅初次指定集装载成功时使用；切集与成功起播后的重试不再消费。 */
    resumeMs: Long = 0L,
    initialMetadata: com.starveil.core.model.ShortPlay? = null,
) : ViewModel() {

    private val scope: CoroutineScope = deps.scope ?: viewModelScope
    private val reporter = ProgressReporter(deps.clock, deps.progressSink, scope)
    private var pendingResumeMs: Long? = resumeMs.coerceAtLeast(0L)
    private var loadJob: Job? = null
    private val trySee = VerticalTrySeeContext(TrySeeGate(deps.trySeeAccess, deps.trySeeMinutes))

    /**
     * 弹幕时间轴。关闭时给控制器传 null：不拉取、不上屏，[danmu] 恒为空，
     * 页面上的 DanmuLayer 直接返回——不新开接口。
     */
    private val danmuController = DanmuController(
        source = deps.danmuSource.takeIf { danmuEnabled },
        scope = scope,
        // 短剧只有一条源（VERTICAL_PLAY_SOURCE_INDEX），集号用当前集下标
        videoRef = { DanmuTarget(vodId, VERTICAL_PLAY_SOURCE_INDEX, currentEpisodeIndex()) },
        clock = deps.clock,
    )

    /** 当前 10 秒窗口内应显示的弹幕；DanmuLayer 只消费这个列表。 */
    val danmu: StateFlow<List<DanmuItem>> = danmuController.items

    /** 下一集地址的预取池；最小可用版本，真正的 PlayerPool 见 [VerticalPreloadPool]。 */
    internal val preloader = VerticalPreloadPool()

    /** 当前源的播放信息：请求头只在命中 issethead 正则时生效。 */
    private var playerInfo: PlayerInfo? = null

    private val _state = MutableStateFlow(verticalInitialState(initialMetadata, vodRepository.cachedDetail(vodId)))
    val state: StateFlow<VerticalPlayUiState> = _state.asStateFlow()

    internal val playback = VerticalPlaybackStore(::ownsPlayback)

    /** 预览交接 / 原地全集的编排；语义与顺序见 [VerticalPreviewCoordinator]。 */
    private val handoff = VerticalPreviewCoordinator(
        state = _state, playback = playback, trySee = trySee,
        host = VerticalPreviewHost(::onPageChanged, ::onProgress, ::onPlaybackSuspended, ::restorePreview),
    )

    val defaultPlaybackToken: Any get() = handoff.defaultPlaybackToken
    val hasPreviewHandoff: Boolean get() = handoff.isActive
    fun ownsPlayback(token: Any): Boolean = handoff.owns(token)

    fun beginInlineSeries(episodeNumber: Int): Boolean = handoff.beginInlineSeries(episodeNumber, playerInfo)

    fun endInlineSeries(): Boolean = handoff.endInlineSeries()

    fun beginPreviewHandoff(token: Any, episodeNumber: Int): Boolean =
        handoff.beginPreviewHandoff(token, episodeNumber, playerInfo)

    fun endPreviewHandoff(token: Any): Boolean = handoff.endPreviewHandoff(token)

    private fun restorePreview(snapshot: VerticalPreviewSnapshot) {
        loadJob?.cancel()
        playerInfo = snapshot.playerInfo
        preloader.clear()
        trySee.gate = snapshot.gate
        trySee.episodeNumber = snapshot.gateEpisode
        _state.value = snapshot.state
        reporter.reset()
        reporter.onPlaybackStarted()
        danmuController.reset()
    }

    override fun onCleared() {
        playback.clear()
        handoff.clear()
        super.onCleared()
    }

    init {
        load()
    }

    /** 错误态重试。 */
    fun retry() {
        val current = _state.value
        if (current.playingUrl == null || current.errorMessage == null) {
            load()
        } else {
            _state.value = current.copy(
                errorMessage = null,
                isPlaying = !current.isBlocked,
                isBuffering = true,
                playbackRevision = current.playbackRevision + 1L,
            )
        }
    }

    /**
     * pager 切页。页号越界时夹到最后一集，不让一个脏下标把播放地址清空。
     *
     * 同一页的重复回调（pager 首次布局、重组）直接忽略，否则会把进度重置成 0。
     */
    fun onPageChanged(page: Int) {
        val current = _state.value
        if (current.episodes.isEmpty()) return
        val index = page.coerceIn(0, current.episodes.lastIndex)
        if (index == current.currentIndex && current.playingUrl != null) return
        pendingResumeMs = null
        play(current, index)
    }

    /** 保存位置后过试看闸门，再记账上报，最后喂弹幕时间轴。 */
    fun onProgress(positionMs: Long, durationMs: Long, isPlaying: Boolean) {
        _state.update {
            it.copy(positionMs = positionMs.coerceAtLeast(0L), durationMs = durationMs.coerceAtLeast(0L))
        }
        if (applyGate(positionMs)) return
        reporter.onTick(isPlaying, VERTICAL_PLAY_SOURCE_INDEX, _state.value.currentIndex)
        danmuController.onPosition()
    }

    /** 播放/暂停按钮。 */
    fun onPlayPauseClick() = togglePlayback()


    private fun togglePlayback() {
        val current = _state.value
        if (current.isBlocked || current.errorMessage != null) return
        val ended = current.durationMs > 0L && current.positionMs >= current.durationMs
        if (ended && !current.isPlaying) onSeekTo(0L)
        _state.update { it.copy(isPlaying = !current.isPlaying) }
    }

    fun onSeekTo(positionMs: Long) {
        _state.update {
            it.copy(positionMs = positionMs.coerceAtLeast(0L), seekRevision = it.seekRevision + 1L)
        }
        applyGate(positionMs)
        reporter.onSeek()
    }

    /** 隐藏/后台期间不采样，也不能把这段墙钟时间算进观看时长。 */
    fun onPlaybackSuspended() = reporter.onSeek()

    fun onBufferingChanged(active: Boolean) = _state.update { it.copy(isBuffering = active) }

    /** 仅接收当前集的权限结果；app 同步权限期间用 options.active 阻止播放。 */
    fun updateTrySee(episodeNumber: Int, gate: TrySeeGate, token: Any = defaultPlaybackToken) {
        if (!ownsPlayback(token)) return
        _state.update { trySee.update(it, episodeNumber, gate) }
    }

    /**
     * 剧内默认连播下一集；预览播完只暂停，下一次单击可以重播本集。
     *
     * 只改 currentIndex，pager 跟随交给 `VerticalPagerSync`；不回调 [onPageChanged]，
     * 否则落页回调会把地址与上报基准再重置一遍。
     */
    fun onPlaybackEnded(preview: Boolean = false) {
        val current = _state.value
        val next = current.currentIndex + 1
        val stopped = preview || current.isBlocked
        val hasNext = next <= current.episodes.lastIndex
        if (stopped || !hasNext) {
            _state.update { it.copy(isPlaying = false, isBuffering = false) }
            return
        }
        play(current, next)
    }

    fun onPlaybackError(message: String) {
        val hint = playerErrorMessage(message, triedOtherSources = false)
        _state.update { it.copy(errorMessage = hint, isPlaying = false, isBuffering = false) }
    }

    private fun load() {
        loadJob?.cancel()
        preloader.clear()
        val revision = _state.value.playbackRevision + 1L
        _state.value = _state.value.metadataOnly().copy(playbackRevision = revision)
        loadJob = scope.launch {
            val result = vodRepository.detail(vodId)
            ensureActive()
            when (result) {
                is AppResult.Success -> applyVod(result.value)
                is AppResult.Failure -> _state.update {
                    it.copy(isLoading = false, errorMessage = verticalErrorMessage(result.error))
                }
            }
        }
    }

    private fun applyVod(vod: Vod) {
        playerInfo = vod.playSources.getOrNull(VERTICAL_PLAY_SOURCE_INDEX)?.playerInfo
        val previous = _state.value
        val base = verticalStateOf(vod).copy(
            title = vod.name.ifBlank { previous.title },
            cover = vod.poster?.takeIf(String::isNotBlank) ?: previous.cover,
            description = vod.blurb?.takeIf(String::isNotBlank) ?: previous.description,
            category = vod.typeName?.takeIf(String::isNotBlank) ?: previous.category,
            playbackRevision = previous.playbackRevision,
        )
        if (base.episodes.isEmpty()) {
            _state.value = base
            return
        }
        val start = base.episodes.indexOfFirst { it.number == initialEpisodeNumber }.coerceAtLeast(0)
        val resume = pendingResumeMs.takeIf { base.episodes[start].number == initialEpisodeNumber } ?: 0L
        play(base, start, resume)
        if (_state.value.playingUrl != null) pendingResumeMs = null
    }

    /**
     * 装一集：预取命中直接用，未命中才归一化 → 请求头判定 → 重置进度与上报基准。
     *
     * 命中只省一次归一化，真正的收益在将来换成"已 prepared 的播放器"
     * （见 [VerticalPreloadPool] 的说明）；命中与否都不改播放器状态机。
     */
    private fun play(base: VerticalPlayUiState, index: Int, positionMs: Long = 0L) {
        val episode = base.episodes.getOrNull(index) ?: return
        val url = preloader.take(index) ?: ParseRules.normalizePlayUrl(episode.url)
        reporter.reset()
        reporter.onPlaybackStarted()
        danmuController.reset()
        _state.value = base.copy(
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
    }

    /** 当前集下标（0 起）。短剧弹幕与 CMS 弹幕表用同一套坐标。 */
    private fun currentEpisodeIndex(): Int = _state.value.currentIndex.coerceAtLeast(0)

    /** 到期后停播并显示当前权限覆盖层，保留地址与位置供权限同步后继续。 */
    private fun applyGate(positionMs: Long): Boolean {
        _state.update { trySee.apply(it, positionMs) }
        return _state.value.isBlocked
    }

    companion object {
        fun factory(
            vodId: Int,
            vodRepository: VodRepository,
            deps: PlayerDeps,
            episodeNumber: Int = FIRST_EPISODE_NUMBER,
            resumeMs: Long = 0L,
            initialMetadata: com.starveil.core.model.ShortPlay? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                VerticalPlayViewModel(
                    vodId, vodRepository, deps, episodeNumber, resumeMs = resumeMs, initialMetadata = initialMetadata,
                )
            }
        }
    }
}

/** 只预取下一集地址，不创建播放器；prepare 仍由 session 统一协调。 */
private fun VerticalPreloadPool.prefetchNext(episodes: List<VerticalEpisode>, index: Int) {
    val next = episodes.getOrNull(index + 1) ?: return
    val url = ParseRules.normalizePlayUrl(next.url) ?: return
    prefetch(index + 1, url)
}
