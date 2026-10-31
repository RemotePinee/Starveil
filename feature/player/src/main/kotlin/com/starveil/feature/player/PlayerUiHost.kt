package com.starveil.feature.player

import com.starveil.core.model.PlaybackMachine
import com.starveil.core.model.PlaybackPhase
import com.starveil.core.model.PlaybackSession
import com.starveil.core.model.TrySeeAccess
import com.starveil.core.model.Vod

/**
 * 播放页可变状态的访问口。
 *
 * 存在的理由：
 * 1. "用户操作 → 状态迁移"收进本文件这组扩展函数，PlayerViewModel 只做转接，
 *    类行数才守得住 detekt 的 `LargeClass`（200 行）；
 * 2. 迁移逻辑不直接摸 VM 私有字段，改动面一眼可见。
 */
internal interface PlayerUiHost {

    /**
     * 当前影片详情。**可变**：付费墙打开时这一集的地址是空的，解锁后重取的那份才带真实
     * 地址，起播读的就是这里（见 `PlayerParseOps.reloadDetailAfterUnlock`）。
     */
    var vod: Vod

    val uiState: PlayerUiState

    /** 试看参数可变：买断/开会员回来后权限会变，而 VM 不能重建。 */
    var trySeeAccess: TrySeeAccess

    /** 可看窗口（秒），服务端 `trysee_seconds` 原值；0 表示没有可看窗口。 */
    var trySeeSeconds: Int

    fun edit(transform: (PlayerUiState) -> PlayerUiState)

    fun emit(event: PlayerEvent)

    /** 重新解析（换集 / 换线 / 重试都走它）。 */
    fun restart()

    fun readSession(): PlaybackSession

    fun writeSession(session: PlaybackSession)

    /** 保存播放位置（观看记录 + 断点续播）。 */
    fun persist(positionMs: Long)

    /**
     * 当前播放位置（毫秒）。
     *
     * 单独一条流、不进 [PlayerUiState]：播放器每 500ms 报一次位置，写进 UiState
     * 等于整页 2Hz 重组（见 `PlayerViewModel.position`）。
     */
    var currentPositionMs: Long
}

/** 播放 / 暂停（用户点击，切换）。 */
internal fun PlayerUiHost.togglePlay() = edit {
    it.copy(isPlaying = !it.isPlaying, isPauseAdEligible = it.isPlaying)
}

/** 全屏开关：真正的横竖屏切换属于 :app。 */
internal fun PlayerUiHost.toggleFullscreen() = edit { it.copy(isFullscreen = !it.isFullscreen) }

/** 倍速：只接受档位表里的值。 */
internal fun PlayerUiHost.selectSpeed(speed: Float) {
    if (speed in PlayerSpeeds.STEPS) edit { it.copy(speed = speed) }
}

internal fun PlayerUiHost.reportBuffering(active: Boolean) = edit { it.copy(isBuffering = active) }

/**
 * 播放结束：自动接下一集。
 *
 * 播到最后一集就停在末帧，不回绕也不重播同一集——重播会让用户以为播放器抽风。
 */
internal fun PlayerUiHost.onEnded() {
    val episodes = sourceOf(vod, readSession())?.episodes.orEmpty()
    val next = readSession().episodeIndex + 1
    if (next in episodes.indices) {
        // 先告诉 UI"正在播下一集"，否则中间只有解析转圈，看着像卡住了
        emit(PlayerEvent.AutoPlayNext(episodes[next].title))
        selectEpisode(next)
    } else {
        edit { it.copy(isPlaying = false, isPauseAdEligible = false) }
    }
}

/** 选集：状态机负责重置失败集合。 */
internal fun PlayerUiHost.selectEpisode(episodeIndex: Int) {
    val session = readSession()
    if (episodeIndex !in sourceOf(vod, session)?.episodes.orEmpty().indices) return
    writeSession(PlaybackMachine.selectEpisode(session, episodeIndex))
    restart()
}

/** 换源。找不到同集时取最近的可用下标，不递归。 */
internal fun PlayerUiHost.selectSource(sourceIndex: Int) {
    val source = vod.playSources.getOrNull(sourceIndex) ?: return
    val session = readSession()
    val switched = session.copy(
        parseTargets = source.playerInfo.parseTargets,
        playerInfo = source.playerInfo,
    )
    writeSession(
        PlaybackMachine.selectSource(switched, sourceIndex, episodeIndexFor(vod, session, source)),
    )
    restart()
}

/**
 * 更新试看权限（购买单集 / 开通会员 / 看完广告回来后调用）。
 *
 * 权限放宽且当前正被拦截时直接重来一遍解析，用户不用手动再点一次。
 * **解锁那一次（非 FREE → FREE）不在这里重试**：付费墙打开时服务端把这一集的地址
 * 全部置空，拿旧详情重试只会立刻"全线解析失败"，所以只回报 true，交给
 * `PlayerViewModel.updateAccess` 先重取详情（`PlayerParseOps.reloadDetailAfterUnlock`）。
 *
 * @return true 表示这是一次解锁，调用方需要先把详情里的地址换成新的
 */
internal fun PlayerUiHost.applyAccess(access: TrySeeAccess, seconds: Int): Boolean {
    if (access == trySeeAccess && seconds == trySeeSeconds) return false
    val unlocked = access == TrySeeAccess.FREE && trySeeAccess != TrySeeAccess.FREE
    trySeeAccess = access
    trySeeSeconds = seconds
    if (!unlocked && readSession().phase is PlaybackPhase.Blocked) retryPlayback()
    return unlocked
}

/** 失败覆盖层的「重试」：清空失败集合重来（用户主动，不是自动重试）。 */
internal fun PlayerUiHost.retryPlayback() {
    writeSession(readSession().copy(failedTargets = emptySet()))
    restart()
}

/**
 * 后台 / 离开页面时暂停（幂等）。
 *
 * 不能用 [togglePlay]：ON_PAUSE 与 ON_STOP 各来一次，两次 toggle 又回到播放，
 * 用户看到的就是"退后台回来按钮还是暂停态，得点两下"。
 */
internal fun PlayerUiHost.pausePlayback() {
    edit { it.copy(isPlaying = false, isPauseAdEligible = false) }
    persist(currentPositionMs)
}
