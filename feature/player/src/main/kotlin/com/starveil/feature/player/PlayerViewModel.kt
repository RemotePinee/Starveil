package com.starveil.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.starveil.core.model.DanmuTarget
import com.starveil.core.model.PlaybackMachine
import com.starveil.core.model.PlaybackPhase
import com.starveil.core.model.PlaybackSession
import com.starveil.core.model.PlayStream
import com.starveil.core.model.TrySeeAccess
import com.starveil.core.model.Vod
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

private const val MILLIS_PER_SECOND = 1_000L

/** 位置落库步长（秒）：太密费流量，太疏丢进度。 */
private const val PROGRESS_SAVE_STEP_SEC = 5

/**
 * 播放页状态持有者。
 *
 * 唯一可变来源是 [session]；线路下标、失败集合、待回集合全部在 [PlaybackSession] 里，
 * 所有迁移经 [PlaybackMachine]。
 *
 * 分工：
 * - 用户操作 → 状态迁移：`PlayerUiHost.kt` 的扩展函数（本类只转接）；
 * - 换线 / 试看闸门 / 运行时错误：[PlaybackSessionOps]；
 * - 弹幕：[PlayerDanmuDispatcher]。
 */
class PlayerViewModel(
    override val vod: Vod,
    private val deps: PlayerDeps,
    initialSourceIndex: Int = 0,
    initialEpisodeIndex: Int = 0,
    /** 断点续播的起播位置（毫秒）；0 表示从头。只作用于首次起播。 */
    private val resumePositionMs: Long = 0L,
) : ViewModel(), PlayerUiHost {

    private val scope: CoroutineScope = deps.scope ?: viewModelScope
    private val reporter = ProgressReporter(deps.clock, deps.progressSink, scope)

    override var trySeeAccess: TrySeeAccess = deps.trySeeAccess
    override var trySeeMinutes: Int = deps.trySeeMinutes

    private var session: PlaybackSession = initialSessionOf(vod, initialSourceIndex, initialEpisodeIndex)
    /** 上一次已知播放位置：切页回来时引擎重建，用它把进度条直接放在断点上而不是 0。 */
    private var lastKnownPositionMs = 0L
    private var timeoutJob: Job? = null
    private var probeJobs: Map<Int, Job> = emptyMap()
    private var parseGeneration = 0
    /** 上次落库的位置（秒）；换集/重来时重置。 */
    private var lastSavedSec = 0

    private val danmuDispatcher = PlayerDanmuDispatcher(
        source = deps.danmuSource,
        scope = scope,
        videoRef = { DanmuTarget(vod.id, session.sourceIndex, session.episodeIndex) },
        clock = deps.clock,
        emit = ::sendEvent,
    )

    private val _state = MutableStateFlow(sessionStateOf(vod, session, PlayerUiState()))
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _events = Channel<PlayerEvent>(Channel.BUFFERED)
    val events: Flow<PlayerEvent> = _events.receiveAsFlow()

    /** 整集弹幕，DanmuLayer 按播放位置绘制。 */
    val danmu: StateFlow<List<DanmuItem>> = danmuDispatcher.items

    // ---- PlayerUiHost -------------------------------------------------------

    override val uiState: PlayerUiState get() = _state.value

    override fun edit(transform: (PlayerUiState) -> PlayerUiState) {
        _state.value = transform(_state.value)
    }

    override fun emit(event: PlayerEvent) = sendEvent(event)

    override fun restart() = start()

    override fun readSession(): PlaybackSession = session

    override fun writeSession(session: PlaybackSession) {
        this.session = session
    }

    override fun persist(positionMs: Long) {
        val positionSec = (positionMs / MILLIS_PER_SECOND).toInt()
        // 用「与上次落盘位置的绝对差」判断，不能用有符号差：向后拖动后 positionSec 比
        // lastSavedSec 小，差值是负数，永远满足 "< STEP" 而提前 return——进度再也不落盘，
        // 下次进页面断点续播就停在拖动前的位置。
        if (positionSec < 0 || abs(positionSec - lastSavedSec) < PROGRESS_SAVE_STEP_SEC) return
        lastSavedSec = positionSec
        val source = session.sourceIndex
        val episode = session.episodeIndex
        scope.launch { deps.progressSink.savePosition(vod.id, source, episode, positionSec) }
    }

    // ---- 生命周期 -----------------------------------------------------------

    /** 开始（或重新开始）一轮解析。：。 */
    fun start() {
        parseGeneration += 1
        timeoutJob?.cancel()
        cancelProbes()
        reporter.reset()
        danmuDispatcher.reset()
        lastSavedSec = 0
        edit { it.copy(positionMs = 0L, durationMs = 0L, isPlaying = false) }
        session = PlaybackMachine.beginParse(session)
        publish()
        val pending = (session.phase as? PlaybackPhase.Parsing)?.pendingTargets.orEmpty()
        pending.forEach(::launchProbe)
        if (pending.isNotEmpty()) launchTimeout()
    }

    /** 页面进入时调用：已经解析过就不重来（旋转屏幕后 ViewModel 还活着）。 */
    fun ensureStarted() {
        if (session.phase is PlaybackPhase.Idle) start()
    }

    /** 播放器每 500ms 回调一次：先过试看闸门，再记账 + 存位置。 */
    fun onProgress(positionMs: Long, durationMs: Long, isPlaying: Boolean) {
        // seek 还没落地时播放器报的是 0：直接吞掉，否则进度条会先跳回开头再跳回来。
        // 只在 pendingSeekMs 有值的极短窗口生效，不影响正常出帧与试看判定。
        if (_state.value.pendingSeekMs > 0L) return
        lastKnownPositionMs = positionMs
        val blocked = applyTrySeeGate(
            port = port(),
            actions = actions(),
            positionMs = positionMs,
            trySee = TrySeeConfig(access = trySeeAccess, minutes = trySeeMinutes),
        )
        if (!blocked) {
            reporter.onTick(isPlaying, session.sourceIndex, session.episodeIndex)
            if (isPlaying) persist(positionMs)
        }
        edit { it.copy(positionMs = positionMs, durationMs = durationMs) }
        danmuDispatcher.onPosition()
    }

    // ---- 用户操作（转接给 PlayerUiHost 的扩展函数）-------------------------

    /**
     * 暂停贴片是否正占着画面。
     *
     * 贴片在屏时禁止恢复播放：否则双击屏幕就能跳过倒计时，后台配的秒数等于没有。
     * 由 UI 层（`PlayerPauseAdLayer`）推上来，纯展示状态，不进 UiState。
     */
    private var pauseAdHolding = false

    fun onPauseAdHold(holding: Boolean) {
        pauseAdHolding = holding
    }

    fun onPlayPauseClick() {
        if (pauseAdHolding) return
        togglePlay()
    }

    fun onFullscreenToggle() = toggleFullscreen()

    fun onSpeedSelected(speed: Float) = selectSpeed(speed)

    fun onSeekTo(positionMs: Long) {
        edit { it.copy(positionMs = positionMs) }
        reporter.onSeek()
        // 拖完立刻落一次盘：拖到目标位置就退出页面时，500ms 的采样还没轮到，
        // 只靠 onProgress 会丢掉这次拖动。persist 自带 5 秒步长，拖多少次也只写一次。
        persist(positionMs)
    }

    fun onBufferingChanged(active: Boolean) = reportBuffering(active)

    fun onPlaybackEnded() = onEnded()

    /** 播放出错：停掉弹幕轮询，错误覆盖层上不该还飘着字。 */
    fun onPlaybackError(message: String) {
        danmuDispatcher.reset()
        handlePlaybackError(port(), actions(), message)
    }

    fun onEpisodeSelected(episodeIndex: Int) = selectEpisode(episodeIndex)

    fun onSourceSelected(sourceIndex: Int) = selectSource(sourceIndex)

    fun onSwitchLineClick() = switchPlaybackLine(port(), actions())

    fun onRetryClick() = retryPlayback()

    fun onUpgradeClick() = emit(PlayerEvent.RequestUpgrade)

    fun onBuyClick() = emit(PlayerEvent.RequestPurchase)

    fun updateAccess(access: TrySeeAccess, minutes: Int) = applyAccess(access, minutes)

    fun pausePlayback() = pausePlaybackImpl()

    /** 退后台 / 离页补一次位置。 */
    fun persistCurrentPosition() = persist(_state.value.positionMs)

    /** 播放器已按 [PlayerUiState.pendingSeekMs] 定位完成，清标记避免重复 seek。 */
    fun onSeekConsumed() = edit { it.copy(pendingSeekMs = 0L) }

    /** 发送弹幕；失败由 [PlayerDanmuDispatcher] 把后端原话透出来。 */
    fun sendDanmu(text: String, colorHex: String = "") {
        danmuDispatcher.send(content = text, atTimeMs = _state.value.positionMs, colorHex = colorHex)
    }

    // ---- 解析 ---------------------------------------------------------------

    /** 探测单条线路；探测抛异常按"没拿到地址"处理，不让线路把页面带崩。 */
    private fun launchProbe(targetIndex: Int) {
        val target = session.parseTargets.getOrNull(targetIndex).orEmpty()
        // 解析站要的是"这一集的原始地址"，不同集数必须分开探测
        val vodUrl = episodeOf(vod, session)?.url.orEmpty()
        val generation = parseGeneration
        val job = scope.launch {
            val stream = runCatching { deps.probe.probe(targetIndex, target, vodUrl) }.getOrNull()
            // 被取消或被新一轮解析取代的结果一律丢弃
            if (generation == parseGeneration) onProbeResult(targetIndex, stream)
        }
        probeJobs = probeJobs + (targetIndex to job)
    }

    private fun onProbeResult(targetIndex: Int, stream: PlayStream?) {
        val pending = (session.phase as? PlaybackPhase.Parsing)?.pendingTargets.orEmpty()
        // 迟到结果：该线路已失败、或已经拿到别的地址，丢弃
        if (targetIndex !in pending) return
        session = PlaybackMachine.onParsed(session, targetIndex, stream)
        if (session.phase is PlaybackPhase.Playing) {
            timeoutJob?.cancel()
            cancelProbes()
            onPlaybackStarted()
        } else {
            // 失败也要先 publish：只更新 session 不推状态，UI 会一直停在 Parsing
            publish()
            settleParse()
        }
    }

    /** 8 秒到点：在途线路全部记失败并取消在途探测。 */
    private fun launchTimeout() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(PlaybackMachine.LINE_TIMEOUT_MS)
            val pending = (session.phase as? PlaybackPhase.Parsing)?.pendingTargets.orEmpty()
            if (pending.isEmpty()) return@launch
            session = PlaybackMachine.onTimeout(session)
            cancelProbes()
            publish()
            settleParse()
        }
    }

    /** 状态机停在"空 Parsing"时重新驱动一轮，避免卡死。 */
    private fun settleParse() {
        val phase = session.phase as? PlaybackPhase.Parsing ?: return
        if (phase.pendingTargets.isNotEmpty()) return
        start()
    }

    private fun onPlaybackStarted() {
        reporter.onPlaybackStarted()
        // 回页续接优先用 VM 里留的上次位置：否则 UI 先显示 0（进度条停在开头），
        // 等 seek 落地才跳到断点，看起来像"闪一下"。
        val resume = maxOf(resumePositionMs.coerceAtLeast(0L), lastKnownPositionMs)
        lastKnownPositionMs = 0L
        edit {
            it.copy(
                isPlaying = true,
                isBuffering = true,
                isPauseAdEligible = false,
                positionMs = resume,
                pendingSeekMs = resume,
            )
        }
        publish()
    }

    // ---- 基础设施 -----------------------------------------------------------

    private fun port() = PlaybackSessionPort(
        vodName = vod.name,
        read = { session },
        write = { session = it },
    )

    private fun actions() = PlaybackActions(
        setState = { transform -> edit(transform) },
        emit = ::sendEvent,
        triggers = PlaybackTriggers(
            restart = ::start,
            probe = ::launchProbe,
            armTimeout = ::launchTimeout,
            republish = ::publish,
        ),
    )

    private fun cancelProbes() {
        probeJobs.values.forEach { it.cancel() }
        probeJobs = emptyMap()
    }

    private fun sendEvent(event: PlayerEvent) {
        _events.trySend(event)
    }

    private fun publish() {
        _state.value = sessionStateOf(vod, session, _state.value)
        // 解析结果里带了官方弹幕地址就拉一次（内部按 url 去重）
        (session.phase as? PlaybackPhase.Playing)?.danmuUrl?.let(danmuDispatcher::ensureOfficial)
    }

    /** 与扩展函数 [pausePlayback] 同名冲突，这里显式转发。 */
    private fun pausePlaybackImpl() = (this as PlayerUiHost).pausePlayback()
}
