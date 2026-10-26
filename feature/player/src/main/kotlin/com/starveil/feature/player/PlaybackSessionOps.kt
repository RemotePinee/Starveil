package com.starveil.feature.player

import com.starveil.core.model.PlaybackMachine
import com.starveil.core.model.PlaybackPhase
import com.starveil.core.model.PlaybackSession
import com.starveil.core.model.TrySeeAccess

/** 会话读写口：函数内部只处理迁移，不持有 VM 的字段。 */
internal class PlaybackSessionPort(
    val vodName: String,
    val read: () -> PlaybackSession,
    val write: (PlaybackSession) -> Unit,
)

/** 重新解析所需的动作（重来 / 探某条线路 / 起超时）。 */
internal class PlaybackTriggers(
    val restart: () -> Unit,
    val probe: (Int) -> Unit,
    val armTimeout: () -> Unit,
    /** 把最新 session 推成 UI 状态（命中闸门 / 换线后必须刷一次）。 */
    val republish: () -> Unit,
)

/** 需要回调 VM 的动作。拆开是为了让下面几个函数的参数不超过 5 个。 */
internal class PlaybackActions(
    val setState: ((PlayerUiState) -> PlayerUiState) -> Unit,
    val emit: (PlayerEvent) -> Unit,
    val triggers: PlaybackTriggers,
)

/** 试看参数：权限类型 + 可看分钟数。 */
internal class TrySeeConfig(
    val access: TrySeeAccess,
    val minutes: Int,
)

/**
 * 播放中的运行时错误。
 *
 * 最典型的是 403：解析出来的直链带防盗链或已过期，探测阶段能拿到地址、真正拉流才被拒。
 * 这种情况不该把用户钉在错误页上——把当前线路记失败，用剩下的线路再解析一轮；
 * 全部线路都试过才落成错误文案。
 */
internal fun handlePlaybackError(port: PlaybackSessionPort, actions: PlaybackActions, message: String) {
    val session = port.read()
    val text = playerErrorMessage(message)
    val playing = session.phase as? PlaybackPhase.Playing
    if (playing != null && session.failedTargets.size + 1 < session.parseTargets.size) {
        val failed = PlaybackMachine.recordFailure(session, playing.targetIndex)
        port.write(failed)
        actions.triggers.republish()
        actions.setState { it.copy(errorMessage = null, isPlaying = false, isBuffering = true) }
        val pending = (failed.phase as? PlaybackPhase.Parsing)?.pendingTargets.orEmpty()
        pending.forEach(actions.triggers.probe)
        if (pending.isNotEmpty()) actions.triggers.armTimeout()
        return
    }
    actions.setState { it.copy(errorMessage = text, isBuffering = false) }
    actions.emit(PlayerEvent.PlaybackFailed(text))
}

/** 换线路：先把当前线路记失败再重解析，否则"换线路"点下去像没反应。 */
internal fun switchPlaybackLine(port: PlaybackSessionPort, actions: PlaybackActions) {
    val session = port.read()
    val from = (session.phase as? PlaybackPhase.Playing)?.targetIndex
    port.write(
        if (from == null) {
            session.copy(failedTargets = emptySet())
        } else {
            PlaybackMachine.recordFailure(session, from)
        },
    )
    actions.triggers.restart()
}

/** 失败覆盖层的「重试」：清空失败集合重来（用户主动，不是自动重试）。 */
internal fun retryPlayback(port: PlaybackSessionPort, actions: PlaybackActions) {
    port.write(port.read().copy(failedTargets = emptySet()))
    actions.triggers.restart()
}

/**
 * 试看闸门。
 *
 * @return true 表示本轮命中了拦截（调用方要跳过进度记账）
 */
internal fun applyTrySeeGate(
    port: PlaybackSessionPort,
    actions: PlaybackActions,
    positionMs: Long,
    trySee: TrySeeConfig,
): Boolean {
    val session = port.read()
    val next = PlaybackMachine.gate(session, positionMs, trySee.access, trySee.minutes)
    if (next === session) return false
    port.write(next)
    actions.triggers.republish()
    actions.setState { it.copy(isPlaying = false) }
    (next.phase as? PlaybackPhase.Blocked)?.let { actions.emit(PlayerEvent.TrySeeBlocked(it.access)) }
    return true
}
