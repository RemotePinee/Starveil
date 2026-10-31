package com.starveil.feature.player

import com.starveil.core.model.PlaybackPhase
import com.starveil.core.model.PlaybackSession
import com.starveil.core.model.PlaySource
import com.starveil.core.model.PlayUrl
import com.starveil.core.model.TrySeeAccess
import com.starveil.core.model.TrySeeCopy
import com.starveil.core.model.Vod

/**
 * 会话 → UI 状态的映射。
 *
 * 单独成文件有两个原因：ViewModel 的类行数上限（detekt LargeClass 200 行），
 * 以及"展示文案只在这里拼"——把提示语散在 Activity 里，改一句要全局搜。
 */

/** 初始会话。集数越界时夹到最后一集；解析线路来自 PlayerInfo.parseTargets。 */
internal fun initialSessionOf(vod: Vod, sourceIndex: Int, episodeIndex: Int): PlaybackSession {
    val index = if (vod.playSources.getOrNull(sourceIndex) != null) sourceIndex else 0
    val source = vod.playSources.getOrNull(index)
    val lastEpisode = (source?.episodes.orEmpty().size - 1).coerceAtLeast(0)
    return PlaybackSession(
        sourceIndex = index,
        episodeIndex = episodeIndex.coerceIn(0, lastEpisode),
        parseTargets = source?.playerInfo?.parseTargets.orEmpty(),
        playerInfo = source?.playerInfo,
    )
}

/** 用会话覆盖展示字段，播放时序字段（位置/倍速/全屏）沿用 base。 */
internal fun sessionStateOf(
    vod: Vod,
    session: PlaybackSession,
    base: PlayerUiState,
): PlayerUiState = PlayerUiState(
    phase = session.phase,
    sourceIndex = session.sourceIndex,
    episodeIndex = session.episodeIndex,
    episodeTitle = episodeOf(vod, session)?.title.orEmpty(),
    episodes = episodesOf(vod, session),
    qualities = qualitiesOf(vod),
    trySeeNotice = trySeeNoticeOf(session.phase),
    errorMessage = errorMessageOf(session.phase),
    durationMs = base.durationMs,
    speed = base.speed,
    isPlaying = base.isPlaying,
    isFullscreen = base.isFullscreen,
    isPauseAdEligible = base.isPauseAdEligible,
    isBuffering = base.isBuffering,
    // 断点续播：待定位标记要跟着状态一起搬。漏了会被 publish 抹成 0，
    // 表现是"进度读到了、参数也传了，就是没有 seek"
    pendingSeekMs = base.pendingSeekMs,
)

internal fun qualitiesOf(vod: Vod): List<QualityOption> = vod.playSources.map { source ->
    QualityOption(sourceIndex = source.index, label = source.name.ifBlank { "线路 ${source.index + 1}" })
}

internal fun sourceOf(vod: Vod, session: PlaybackSession): PlaySource? =
    vod.playSources.getOrNull(session.sourceIndex)

internal fun episodeOf(vod: Vod, session: PlaybackSession): PlayUrl? =
    sourceOf(vod, session)?.episodes?.getOrNull(session.episodeIndex)

/** 当前源的选集标题；选集弹层的宫格按下标高亮，不在这里做过滤。 */
internal fun episodesOf(vod: Vod, session: PlaybackSession): List<String> =
    sourceOf(vod, session)?.episodes?.map { it.title }.orEmpty()

/**
 * 换源后当前集在新源里的下标。
 *
 * 在找不到同一集时**递归**换下一个源，没有深度上限（PlaybackMachine.selectSource
 * 注释）；这里只做区间夹取，把"该源没有这一集"变成明确的可用下标。
 */
internal fun episodeIndexFor(vod: Vod, session: PlaybackSession, source: PlaySource): Int {
    val number = episodeOf(vod, session)?.episodeNumber
    val matched = source.episodes.indexOfFirst { it.episodeNumber == number }
    if (matched >= 0) return matched
    return session.episodeIndex.coerceIn(0, (source.episodes.size - 1).coerceAtLeast(0))
}

/** 付费墙说明文案（TrySeeRules 判定后的展示层）。 */
internal fun trySeeNoticeOf(phase: PlaybackPhase): String? = when (phase) {
    // NEED_VIP / NEED_PAY 只是历史取值：服务端已改成单开关，两档共用一套文案
    is PlaybackPhase.Blocked -> if (phase.access == TrySeeAccess.FREE) null else TrySeeCopy.HINT
    else -> null
}

/** 失败文案。线路全败与播放器报错是两回事，前者由状态机给出，后者走事件。 */
internal fun errorMessageOf(phase: PlaybackPhase): String? = when (phase) {
    is PlaybackPhase.Failed -> "全部 ${phase.triedTargets} 条线路解析失败，请换线路或稍后重试"
    else -> null
}
