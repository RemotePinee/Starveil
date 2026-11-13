package com.starveil.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.core.data.VodRepository
import com.starveil.core.model.DanmuTarget
import com.starveil.core.model.PlayerInfo
import com.starveil.core.model.TrySeeGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
    // 装载链路拆在 VerticalPlayLoading.kt，那里的扩展函数要读这两项
    vodId: Int,
    internal val vodRepository: VodRepository,
    private val deps: PlayerDeps,
    initialEpisodeNumber: Int = FIRST_EPISODE_NUMBER,
    /** 短剧默认不开弹幕（计划 §5 P3）；打开时用 [PlayerDeps.danmuSource]。 */
    private val danmuEnabled: Boolean = false,
    /** 仅初次指定集装载成功时使用；切集与成功起播后的重试不再消费。 */
    resumeMs: Long = 0L,
    initialMetadata: com.starveil.core.model.ShortPlay? = null,
) : ViewModel() {

    /**
     * 当前这部剧。整个短剧页只有一个实例，切剧由 [load] 原地改写：
     * 按剧各建一个 VM 会让刷过的每一部剧（每部几百集）都留在 ViewModelStore 里。
     */
    internal var vodId: Int = vodId
        private set

    /** 当前这部剧的入口集号；[load] 换剧时一起换，否则新剧会按上一部剧的集号装载。 */
    internal var initialEpisodeNumber: Int = initialEpisodeNumber
        private set

    /** 换剧要把试看闸门退回初始值：上一部剧的权限不能带到下一部剧。 */
    private val trySeeDefault = TrySeeGate(deps.trySeeAccess, deps.trySeeSeconds)

    internal val scope: CoroutineScope = deps.scope ?: viewModelScope
    internal val reporter = ProgressReporter(deps.clock, deps.progressSink, scope)
    private var lastSavedSec = -1
    internal var pendingResumeMs: Long? = resumeMs.coerceAtLeast(0L)
    internal var loadJob: Job? = null
    internal val trySee = VerticalTrySeeContext(trySeeDefault)

    /**
     * 弹幕时间轴。关闭时给控制器传 null：不拉取、不上屏，[danmu] 恒为空，
     * 页面上的 DanmuLayer 直接返回——不新开接口。
     */
    internal val danmuController = DanmuController(
        source = deps.danmuSource.takeIf { danmuEnabled },
        scope = scope,
        // 短剧只有一条源（VERTICAL_PLAY_SOURCE_INDEX），集号用当前集下标
        // nid 用当前集下标原值：弹幕表存的也是 0 起索引，加 1 会整体偏移一集
        videoRef = {
            DanmuTarget(currentVodId(), VERTICAL_PLAY_SOURCE_INDEX, currentEpisodeIndex())
        },
        clock = deps.clock,
    )

    /** 当前 10 秒窗口内应显示的弹幕；DanmuLayer 只消费这个列表。 */
    val danmu: StateFlow<List<DanmuItem>> = danmuController.items

    /** 下一集地址的预取池；最小可用版本，真正的 PlayerPool 见 [VerticalPreloadPool]。 */
    internal val preloader = VerticalPreloadPool()

    /** 当前源的播放信息：请求头只在命中 issethead 正则时生效。 */
    internal var playerInfo: PlayerInfo? = null

    private val _state = MutableStateFlow(
        verticalInitialState(vodId, initialMetadata, vodRepository.cachedDetail(vodId)),
    )
    val state: StateFlow<VerticalPlayUiState> = _state.asStateFlow()

    /** 可写状态流：装载链路（[VerticalPlayLoading]）在这里推进状态，与类内写法保持一份。 */
    internal val stateFlow: MutableStateFlow<VerticalPlayUiState> get() = _state

    /**
     * 播放器插槽：只由组合层写入（`VerticalPlayRoute` 的 SideEffect），VM 自己不建、不释放。
     *
     * 整个短剧 feed 共用一台引擎，所以这里不再是"每部剧一台播放器"的存根；
     * 写不进来时交接层退回状态里已有的进度，功能不受影响。
     */
    internal val playback = VerticalPlaybackStore()

    /** 本页上屏时把共享播放器接进来；离场 detach，交接层随即不再读它。 */
    internal fun attachPlayback(owner: VerticalPlaybackOwner) = playback.attach(owner)

    internal fun detachPlayback(owner: VerticalPlaybackOwner) = playback.detach(owner)

    /** 预览交接 / 原地全集的编排；语义与顺序见 [VerticalPreviewCoordinator]。 */
    private val handoff = VerticalPreviewCoordinator(
        state = _state, playback = playback, trySee = trySee,
        host = VerticalPreviewHost(::onPageChanged, ::onProgress, ::onPlaybackSuspended) { snapshot ->
            restorePreviewState(snapshot)
        },
    )

    val defaultPlaybackToken: Any get() = handoff.defaultPlaybackToken
    val hasPreviewHandoff: Boolean get() = handoff.isActive
    fun ownsPlayback(token: Any): Boolean = handoff.owns(token)

    fun beginInlineSeries(episodeNumber: Int): Boolean = handoff.beginInlineSeries(episodeNumber, playerInfo)

    fun endInlineSeries(): Boolean = handoff.endInlineSeries()

    fun beginPreviewHandoff(token: Any, episodeNumber: Int): Boolean =
        handoff.beginPreviewHandoff(token, episodeNumber, playerInfo)

    fun endPreviewHandoff(token: Any): Boolean = handoff.endPreviewHandoff(token)

    override fun onCleared() {
        PlayerLog.warn { "vertical vm cleared vod=$vodId" }
        handoff.clear()
        super.onCleared()
    }

    /**
     * 这一屏退出组合：放掉它自己的字节级预取与弹幕时间轴。
     *
     * 播放器**不在这里归还**——整条 feed 只有一台，归 `ShortPlayFeedPlayback` 的
     * DisposableEffect；VM 本身要到整个短剧页出栈才 onCleared。
     * 传 `vodId` 是因为一个实例现在服务多部剧：已经让位给下一部剧的那一屏离场时，
     * 不能顺手把当前这部剧的预取池清掉。
     */
    fun onLeaving(vodId: Int) {
        if (this.vodId != vodId) return
        preloader.clear()
        danmuController.reset()
    }

    /**
     * 换一部剧：整个短剧页只留一个实例，切剧是原地换数据，不新建 VM。
     *
     * 先把上一部剧的重资源放掉再装载——剧集列表、预取池、弹幕时间轴、预览快照都换成本剧的，
     * 否则刷过的每一部剧仍挂在这一个实例上，等于换了名字的泄漏。
     * 同一个 `vodId` 重复调用直接返回：pager 落页与重组都会触发它，重复装载会把进度冲成 0。
     */
    fun load(
        vodId: Int,
        episodeNumber: Int = FIRST_EPISODE_NUMBER,
        resumeMs: Long = 0L,
        metadata: com.starveil.core.model.ShortPlay? = null,
    ) {
        if (this.vodId == vodId) return
        loadJob?.cancel() // 上一次详情请求跟着旧剧一起作废，快速滑动不堆请求
        preloader.clear()
        danmuController.reset()
        reporter.reset()
        handoff.clear()
        this.vodId = vodId
        initialEpisodeNumber = episodeNumber
        pendingResumeMs = resumeMs.coerceAtLeast(0L)
        playerInfo = null
        trySee.gate = trySeeDefault
        trySee.episodeNumber = null
        stateFlow.value = verticalInitialState(vodId, metadata, vodRepository.cachedDetail(vodId))
        loadDetail()
    }

    init {
        // 验收口径：整页一个实例，刷 N 部剧只应看到这一行一次（切剧走 load，不新建）。
        PlayerLog.warn { "vertical vm created vod=$vodId" }
        loadDetail()
    }

    /** 错误态重试。 */
    fun retry() {
        val current = _state.value
        if (current.playingUrl == null || current.errorMessage == null) {
            loadDetail()
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
        playEpisode(current, index)
    }

    /** 保存位置后过试看闸门，再记账上报，最后喂弹幕时间轴。 */
    fun onProgress(positionMs: Long, durationMs: Long, isPlaying: Boolean) {
        _state.update {
            it.copy(positionMs = positionMs.coerceAtLeast(0L), durationMs = durationMs.coerceAtLeast(0L))
        }
        if (applyGate(positionMs)) return
        reporter.onTick(isPlaying, VERTICAL_PLAY_SOURCE_INDEX, _state.value.currentIndex)
        persistPosition(positionMs)
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
    fun onPlaybackSuspended() {
        reporter.onSeek()
        // 退后台/离页时立刻落一次进度：短剧之前只上报观看时长，从不写播放位置，
        // 所以观看记录里永远没有短剧。
        persistPosition(_state.value.positionMs, force = true)
    }

    /** 位置每前进 [VERTICAL_SAVE_STEP_SEC] 秒落一次库；force 用于暂停/离页。 */
    private fun persistPosition(positionMs: Long, force: Boolean = false) {
        if (vodId <= 0 || positionMs <= 0L) return
        val seconds = (positionMs / 1000L).toInt()
        if (!force && seconds - lastSavedSec < VERTICAL_SAVE_STEP_SEC) return
        lastSavedSec = seconds
        val index = _state.value.currentIndex
        scope.launch {
            deps.progressSink.savePosition(vodId, VERTICAL_PLAY_SOURCE_INDEX, index, seconds)
        }
    }

    fun onBufferingChanged(active: Boolean) = _state.update { it.copy(isBuffering = active) }

    /** 仅接收当前集的权限结果；app 同步权限期间用 options.active 阻止播放。 */
    fun updateTrySee(episodeNumber: Int, gate: TrySeeGate, token: Any = defaultPlaybackToken) {
        applyGateUpdate(episodeNumber, gate, token)
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
        playEpisode(current, next)
    }

    fun onPlaybackError(message: String) {
        val hint = playerErrorMessage(message, triedOtherSources = false)
        _state.update { it.copy(errorMessage = hint, isPlaying = false, isBuffering = false) }
    }

    /** 当前集下标（0 起）。短剧弹幕与 CMS 弹幕表用同一套坐标。 */
    private fun currentEpisodeIndex(): Int = _state.value.currentIndex.coerceAtLeast(0)

    /**
     * 当前这部剧。弹幕坐标与详情请求都读它——换剧后不能再用构造时那个 id。
     *
     * 写成函数而不是直接读字段：构造参数同名，属性初始化器里 `vodId` 会解析成构造参数。
     */
    private fun currentVodId(): Int = vodId

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

/** 短剧位置落库步长（秒）：与长剧同一口径。 */
private const val VERTICAL_SAVE_STEP_SEC = 5
