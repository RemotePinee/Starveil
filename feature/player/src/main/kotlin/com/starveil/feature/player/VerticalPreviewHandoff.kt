package com.starveil.feature.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.starveil.core.model.ParseRules
import com.starveil.core.model.PlayerInfo
import com.starveil.core.model.TrySeeAccess
import com.starveil.core.model.TrySeeGate
import com.starveil.core.model.TrySeeRules

internal data class VerticalPreviewSnapshot(
    val state: VerticalPlayUiState,
    val gate: TrySeeGate,
    val gateEpisode: Int?,
    val playerInfo: PlayerInfo?,
)

/** 只属于一个剧的 VM；页面切换转移控制权，不复制引擎，不把 gate 快照当作新前台授权。 */
internal class VerticalPreviewHandoff {
    var previewToken: Any by mutableStateOf(Any())
        private set
    private var owner: Any by mutableStateOf(previewToken)
    private var snapshot: VerticalPreviewSnapshot? = null
    val isActive: Boolean get() = snapshot != null
    var isInline: Boolean = false
        private set

    fun beginInline(preview: VerticalPreviewSnapshot): Boolean {
        if (isActive) return false
        snapshot = preview
        isInline = true
        return true
    }

    fun endInline(): VerticalPreviewSnapshot? {
        val preview = if (isInline) snapshot else null
        if (preview != null) clear()
        return preview
    }

    fun clear() {
        snapshot = null
        isInline = false
        owner = previewToken
    }

    fun owns(token: Any): Boolean = owner === token

    fun begin(token: Any, preview: VerticalPreviewSnapshot): Boolean {
        if (snapshot != null || token === previewToken) return false
        snapshot = preview
        owner = token
        return true
    }

    fun end(token: Any, current: VerticalPlayUiState): VerticalPreviewSnapshot? {
        val preview = snapshot?.takeIf { !isInline && owns(token) } ?: return null
        val saved = preview.state
        val moved = current.mediaSource() != saved.mediaSource() || current.positionMs != saved.positionMs ||
            current.seekRevision != saved.seekRevision
        val restored = preview.copy(
            state = saved.copy(seekRevision = if (moved) current.seekRevision + 1L else current.seekRevision),
        )
        snapshot = null
        previewToken = Any()
        owner = previewToken
        return restored
    }
}

/** 集级 gate 的可恢复上下文；恢复快照并不替代 app 的前台重新校验。 */
internal class VerticalTrySeeContext(var gate: TrySeeGate) {
    // null 保留构造 gate 的语义；后续权限只属于对应集。
    var episodeNumber: Int? = null

    fun update(state: VerticalPlayUiState, episode: Int, updatedGate: TrySeeGate): VerticalPlayUiState {
        if (state.current?.number != episode) return state
        gate = updatedGate
        episodeNumber = episode
        val blocked = if (expired(state, state.positionMs)) gate.access else null
        val unlocked = state.isBlocked && gate.access == TrySeeAccess.FREE
        val canResume = state.playingUrl != null && state.errorMessage == null
        return state.copy(
            blockedAccess = blocked,
            isPlaying = when {
                blocked != null -> false
                unlocked && canResume -> true
                else -> state.isPlaying
            },
        )
    }

    fun apply(state: VerticalPlayUiState, positionMs: Long): VerticalPlayUiState =
        if (!state.isBlocked && expired(state, positionMs)) {
            state.copy(isPlaying = false, blockedAccess = gate.access)
        } else {
            state
        }

    private fun expired(state: VerticalPlayUiState, positionMs: Long): Boolean {
        val sameEpisode = episodeNumber == null || episodeNumber == state.current?.number
        return sameEpisode && gate.access != TrySeeAccess.FREE &&
            TrySeeRules.isExceeded(positionMs, gate.trySeeMinutes)
    }
}

internal fun verticalHeadersOf(url: String?, info: PlayerInfo?): Map<String, String> =
    if (url != null && info != null) {
        ParseRules.resolveHeaders(url, info.headerPatterns, info.headers)
    } else {
        emptyMap()
    }
