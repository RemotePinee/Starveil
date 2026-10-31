package com.starveil.feature.player

import com.starveil.core.model.PlaybackMachine
import com.starveil.core.model.PlaybackPhase
import com.starveil.core.model.PlayStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 解析（线路探测 / 8 秒超时 / 落地）的驱动。
 *
 * 与 [PlaybackSessionOps] 同一思路：函数只做状态迁移，状态本身留在 VM 上。
 * 单独成文件是为了让 [PlayerViewModel] 不突破 200 行的类上限（AGENTS.md 第 2 节）。
 */

/** 探测单条线路；探测抛异常按"没拿到地址"处理，不让线路把页面带崩。 */
internal fun PlayerViewModel.launchProbe(targetIndex: Int) {
    val session = readSession()
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

internal fun PlayerViewModel.onProbeResult(targetIndex: Int, stream: PlayStream?) {
    val pending = (readSession().phase as? PlaybackPhase.Parsing)?.pendingTargets.orEmpty()
    // 迟到结果：该线路已失败、或已经拿到别的地址，丢弃
    if (targetIndex !in pending) return
    writeSession(PlaybackMachine.onParsed(readSession(), targetIndex, stream))
    if (readSession().phase is PlaybackPhase.Playing) {
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
internal fun PlayerViewModel.launchTimeout() {
    timeoutJob?.cancel()
    timeoutJob = scope.launch {
        delay(PlaybackMachine.LINE_TIMEOUT_MS)
        val pending = (readSession().phase as? PlaybackPhase.Parsing)?.pendingTargets.orEmpty()
        if (pending.isEmpty()) return@launch
        writeSession(PlaybackMachine.onTimeout(readSession()))
        cancelProbes()
        publish()
        settleParse()
    }
}

/** 状态机停在"空 Parsing"时重新驱动一轮，避免卡死。 */
internal fun PlayerViewModel.settleParse() {
    val phase = readSession().phase as? PlaybackPhase.Parsing ?: return
    if (phase.pendingTargets.isNotEmpty()) return
    start()
}

internal fun PlayerViewModel.onPlaybackStarted() {
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
