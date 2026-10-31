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
    // 位置走独立那条流（UiState 里没有它了），否则进度条会先显示 0 再被 seek 拉回断点
    currentPositionMs = resume
    edit {
        it.copy(
            isPlaying = true,
            isBuffering = true,
            isPauseAdEligible = false,
            pendingSeekMs = resume,
        )
    }
    publish()
}

/** 签名地址过期后最多重拉一次；再失败就走原来的错误处理，避免地址真失效时无限循环。 */
private const val URL_REFRESH_LIMIT = 1

/** 403 最终落到错误态时用的文案键：和播放器给的 errorCodeName 是同一套映射。 */
private const val HTTP_FORBIDDEN_CODE = "ERROR_CODE_IO_BAD_HTTP_STATUS"

/**
 * 签名播放地址过期（CDN 403）后重拉一次地址。
 *
 * 复用 [launchProbe] 那条链路（[PlayerDeps.probe] → 解析站 → `PlaybackMachine.onParsed`），
 * 只重探**当前线路**。不能走 [PlayerViewModel.start]：那里会把位置、记账、解析代次一起清零，
 * "地址过期重拉"就变成了"从片头重播"。
 *
 * 位置靠 [PlayerUiState.pendingSeekMs] 续上：新地址换进 phase 后，`PlayerWiring` 的 seek
 * effect 会在 prepare 之后定位回 [PlayerViewModel.currentPositionMs]，与断点续播是同一条路径。
 *
 * @return true 表示已发起重拉，调用方不必落错误态；false 表示名额用尽或当前不在播放。
 */
internal fun PlayerViewModel.refreshExpiredUrl(): Boolean {
    val session = readSession()
    val playing = session.phase as? PlaybackPhase.Playing
    // 直链起播没有解析线路可重谈（targetIndex 只是占位 0），这种地址过期只能落错误态
    val target = playing?.let { session.parseTargets.getOrNull(it.targetIndex) }
    // 三件事都交回调用方落错误态：不在播放 / 没有可重谈的线路 / 重拉名额已用尽
    val canRefresh = playing != null && target != null
    if (!canRefresh || urlRefreshAttempts >= URL_REFRESH_LIMIT) return false
    urlRefreshAttempts += 1
    val vodUrl = episodeOf(vod, session)?.url.orEmpty()
    val resumeMs = currentPositionMs
    val generation = parseGeneration
    // 重拉期间先撤掉错误覆盖层：否则地址换成功了，用户还盯着"播放失败"看
    edit { it.copy(errorMessage = null, isBuffering = true) }
    scope.launch {
        val stream = runCatching { deps.probe.probe(playing.targetIndex, target, vodUrl) }.getOrNull()
        // 期间换了集 / 换了线 / 被试看闸门拦下：迟到的地址一律丢弃，不能拿它把状态盖回去
        if (generation != parseGeneration || readSession().phase != playing) return@launch
        if (stream == null || !applyRefreshedStream(playing, stream, resumeMs)) {
            onPlaybackError(HTTP_FORBIDDEN_CODE)
        }
    }
    return true
}

/**
 * 新地址换进 Playing，并把播放位置交给待定位标记（见 [refreshExpiredUrl]）。
 *
 * @return false 表示这份结果换不上去（归一化失败，或地址与原来一模一样），调用方按错误处理。
 */
private fun PlayerViewModel.applyRefreshedStream(
    playing: PlaybackPhase.Playing,
    stream: PlayStream,
    resumeMs: Long,
): Boolean {
    val parsed = PlaybackMachine.onParsed(readSession(), playing.targetIndex, stream)
    val fresh = parsed.phase as? PlaybackPhase.Playing
    // 归一化失败，或者解析站回了一份一模一样的地址（连签名都没变）：这两种都换不上去，
    // 落回错误处理，别让页面停在"缓冲中"转圈——url 不变，PlayerWiring 不会重新 prepare。
    if (fresh == null || fresh.url == playing.url) {
        writeSession(parsed)
        publish()
        return false
    }
    // 这次没带官方弹幕地址就沿用旧的：只换地址，不该把已经拉起来的弹幕关掉
    writeSession(parsed.copy(phase = fresh.copy(danmuUrl = fresh.danmuUrl ?: playing.danmuUrl)))
    // 位置两条路都写：进度流给 UI，pendingSeekMs 给播放器（seek effect 等在 prepare 之后）
    currentPositionMs = resumeMs
    edit { it.copy(pendingSeekMs = resumeMs, isBuffering = true) }
    publish()
    return true
}
