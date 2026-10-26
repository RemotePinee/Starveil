package com.starveil.feature.player

import com.starveil.core.model.PlayerInfo
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 预览交接与原地全集的编排：令牌归属、快照采集与恢复顺序只在这里决定。
 *
 * 播放状态机仍归 [VerticalPlayViewModel]——本类不建播放器、不拉网络、不新增状态源，
 * 只把"什么时候采快照、采完怎么装回去"从 VM 里拆出来。与 [VerticalPreviewHandoff]
 * 的分工：后者只回答"现在有没有快照"，这里回答"谁能采、采到什么、退出时怎么还"。
 */
internal class VerticalPreviewCoordinator(
    private val state: MutableStateFlow<VerticalPlayUiState>,
    private val playback: VerticalPlaybackStore,
    private val trySee: VerticalTrySeeContext,
    private val host: VerticalPreviewHost,
) {

    private val handoff = VerticalPreviewHandoff()

    val defaultPlaybackToken: Any get() = handoff.previewToken
    val isActive: Boolean get() = handoff.isActive

    fun owns(token: Any): Boolean = handoff.owns(token)

    fun clear() = handoff.clear()

    /** 原地进入全集：只采样进度；同一集保持 token、播放意图与媒体修订。 */
    fun beginInlineSeries(episodeNumber: Int, playerInfo: PlayerInfo?): Boolean {
        val current = state.value
        val index = current.episodes.indexOfFirst { it.number == episodeNumber }
        val captured = if (index < 0) null else captureInline(current, playerInfo)
        if (captured == null) return false
        state.value = captured
        if (index != captured.currentIndex) host.onPageChanged(index)
        return true
    }

    /**
     * 原地退出全集：只结束展示模式，播放状态一行不回写。
     *
     * 全集里通常已经翻到别的集（自动预览播完还会接着播下一集），快照描述的是入口那一集；
     * 按快照恢复会把集号、进度和权限一起退回去——"看到第 3 集，返回却回到第 1 集"。
     * 只清 pin（feed 侧的 pin 由 app 的 presentation store 归还）：token、当前集 gate、
     * seek 修订与引擎输出都原样留给 feed，不 prepare、不 seek、不 pause。
     * 导航交接不同：[endPreviewHandoff] 仍按原契约恢复 preview 快照。
     */
    fun endInlineSeries(): Boolean = handoff.endInline() != null

    /** 导航前采样合法帧的位置和播放意图；同一集不改源、不递增 seek/reload 修订。 */
    fun beginPreviewHandoff(token: Any, episodeNumber: Int, playerInfo: PlayerInfo?): Boolean {
        val current = state.value
        val hasPreview = current.current != null && token !== defaultPlaybackToken
        if (handoff.isActive || !hasPreview) return false
        val captured = playback.capture(current)
        host.onProgress(captured.positionMs, captured.durationMs, isPlaying = false)
        host.onPlaybackSuspended()
        val preview = VerticalPreviewSnapshot(state.value, trySee.gate, trySee.episodeNumber, playerInfo)
        val accepted = handoff.begin(token, preview)
        val index = preview.state.episodes.indexOfFirst { it.number == episodeNumber }
        val differentEpisode = index >= 0 && index != preview.state.currentIndex
        if (accepted && differentEpisode) host.onPageChanged(index)
        return accepted
    }

    /** 临时导航不结束交接；真正返回/销毁全集目的地时恢复 preview 上下文。 */
    fun endPreviewHandoff(token: Any): Boolean {
        val navigationHandoff = handoff.isActive && !handoff.isInline
        val owned = navigationHandoff && handoff.owns(token)
        val preview = if (owned) handoff.end(token, playback.capture(state.value)) else null
        if (preview == null) return false
        host.onRestore(preview)
        return true
    }

    /** 权限、集号与快照都合法才登记；不合法返回 null，播放状态一行不动。 */
    private fun captureInline(current: VerticalPlayUiState, playerInfo: PlayerInfo?): VerticalPlayUiState? {
        if (handoff.isActive || current.current == null) return null
        val captured = playback.snapshot(current)
        val preview = VerticalPreviewSnapshot(captured, trySee.gate, trySee.episodeNumber, playerInfo)
        return if (handoff.beginInline(preview)) captured else null
    }
}

/** VM 侧动作入口：交接层只转发，不在这一层判断播放状态或权限。 */
internal class VerticalPreviewHost(
    private val pageChanged: (Int) -> Unit,
    private val progress: (Long, Long, Boolean) -> Unit,
    private val suspended: () -> Unit,
    private val restore: (VerticalPreviewSnapshot) -> Unit,
) {

    fun onPageChanged(index: Int) = pageChanged(index)

    fun onProgress(positionMs: Long, durationMs: Long, isPlaying: Boolean) =
        progress(positionMs, durationMs, isPlaying)

    fun onPlaybackSuspended() = suspended()

    fun onRestore(preview: VerticalPreviewSnapshot) = restore(preview)
}
