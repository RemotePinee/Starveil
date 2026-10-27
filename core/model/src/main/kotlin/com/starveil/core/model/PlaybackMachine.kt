package com.starveil.core.model

/**
 * 播放状态机。
 *
 * 由 `parseData / onJiexiResultListener / changeSelection / changePlaySource`
 * 四段逻辑收敛而来。
 *
 * 把"当前线路下标、失败线路下标、失败集合"三个可变字段散在 Activity 里，
 * 并且有两个真 bug（见 [recordFailure] 与 [beginParse] 的注释）。这里把它
 * 收敛成不可变会话 + 纯函数迁移，Activity/Compose 只消费状态。
 *
 * 语义对齐点：
 * - 历史版本是**并发**给所有未失败线路各起一个探测，
 * 先回先赢，不是串行轮询。所以 [PlaybackPhase.Parsing] 带的是"待回集合"。
 * - 每条线路 8 秒超时，超时按失败处理。
 * - 全部线路都失败才进 [PlaybackPhase.Failed]。
 */
object PlaybackMachine {

    /** 单条解析线路的探测超时（ 的 `time = 8`）。 */
    const val LINE_TIMEOUT_MS = 8_000L

    /**
     * 开始一轮解析。
     *
     * 历史版本在 `curParseIndex == 0` 时清空 failSet，
     * 但同一轮里把**刚成功**的线路号也塞进了 failSet，
     * 导致重试跳过可用线路。这里失败集合只由真实失败写入。
     */
    fun beginParse(session: PlaybackSession): PlaybackSession {
        val pending = session.parseTargets.indices.toSet() - session.failedTargets
        return if (pending.isEmpty()) {
            session.copy(phase = PlaybackPhase.Failed(triedTargets = session.failedTargets.size))
        } else {
            session.copy(phase = PlaybackPhase.Parsing(pendingTargets = pending))
        }
    }

    /**
     * 某条线路返回了结果。
     *
     * 空地址或归一化失败按"该线路失败"处理——历史版本是 `if (url == null || isEmpty)`
     * 后直接换下一条，但没把该线路记进 failSet，会无限重试同一条。
     *
     * 双流（B 站音视频分离）由 [PlayStream.audioUrl] 带过来，原样放进 Playing；
     * 播放器层再用 MergingMediaSource 合成，状态机不关心合并细节。
     */
    fun onParsed(session: PlaybackSession, targetIndex: Int, stream: PlayStream?): PlaybackSession {
        val normalized = ParseRules.normalizeStream(
            rawUrl = stream?.url,
            type = stream?.type,
            audioRaw = stream?.audioUrl,
            // 官方弹幕地址必须带进 Playing，否则播放页拿不到 danmu 地址，官方弹幕永远不显示
            danmuRaw = stream?.danmuUrl,
            // 解析站下发的 Referer 也要带进 Playing，否则 B 站/爱奇艺 CDN 直接 403
            refererRaw = stream?.referer,
        ) ?: return recordFailure(session, targetIndex)

        val fromPlayerInfo = session.playerInfo?.let { info ->
            ParseRules.resolveHeaders(normalized.url, info.headerPatterns, info.headers)
        }.orEmpty()

        // 片源数据在 issethead 命中时显式配过 Referer（大小写不敏感），以它为准；
        // 否则用解析站下发的 referer，补上 B 站/爱奇艺 CDN 的防盗链要求。
        val hasReferer = fromPlayerInfo.keys.any { it.equals(HEADER_REFERER, ignoreCase = true) }
        val headers = if (hasReferer) {
            fromPlayerInfo
        } else {
            normalized.referer?.let { fromPlayerInfo + (HEADER_REFERER to it) } ?: fromPlayerInfo
        }

        return session.copy(
            phase = PlaybackPhase.Playing(
                url = normalized.url,
                targetIndex = targetIndex,
                headers = headers,
                audioUrl = normalized.audioUrl,
                streamType = normalized.type,
                danmuUrl = normalized.danmuUrl,
            ),
        )
    }


    /**
     * 直链起播：地址本身就能播（m3u8 / mp4），不经过解析站。
     *
     * 复用 [onParsed] 的归一化与请求头逻辑，`targetIndex` 固定 0——直链没有解析线路，
     * 万一真的拉流失败，[recordFailure] 记的也是这个不存在的下标，
     * 而 `handlePlaybackError` 里 `failedTargets.size + 1 < parseTargets.size`
     * 在解析站为空时恒假，会直接落到错误提示，不会卡在重试里。
     */
    fun beginDirect(session: PlaybackSession, url: String): PlaybackSession =
        onParsed(session, 0, PlayStream(url = url))

    /** 某条线路探测失败（ 的 `onError` 分支）。 */
    fun recordFailure(session: PlaybackSession, targetIndex: Int): PlaybackSession {
        val failed = session.failedTargets + targetIndex
        val pending = when (val phase = session.phase) {
            is PlaybackPhase.Parsing -> phase.pendingTargets - targetIndex
            else -> session.parseTargets.indices.toSet() - failed
        }

        val nextPhase = if (pending.isEmpty()) {
            PlaybackPhase.Failed(triedTargets = failed.size)
        } else {
            PlaybackPhase.Parsing(pendingTargets = pending)
        }
        return session.copy(failedTargets = failed, phase = nextPhase)
    }

    /**
     * 8 秒到点：把在途线路全部记为失败。
     *
     * 历史版本超时回调成"成功但空地址"，再由上层换线；这里显式归为失败。
     */
    fun onTimeout(session: PlaybackSession): PlaybackSession {
        val pending = session.phase.pendingTargetsOrEmpty()
        if (pending.isEmpty()) return session
        val failed = session.failedTargets + pending
        val phase = if (failed.size >= session.parseTargets.size) {
            PlaybackPhase.Failed(triedTargets = failed.size)
        } else {
            PlaybackPhase.Parsing(pendingTargets = emptySet())
        }
        return session.copy(failedTargets = failed, phase = phase)
    }

    /** 选集：重置解析索引与失败集合。 */
    fun selectEpisode(session: PlaybackSession, episodeIndex: Int): PlaybackSession =
        session.copy(
            episodeIndex = episodeIndex,
            failedTargets = emptySet(),
            phase = PlaybackPhase.Idle,
        )

    /**
     * 换源。
     *
     * 历史版本换源时若新源没有当前集数会**递归**调用自身找下一个源，没有深度上限；
     * 只负责状态，"找不到该集"由调用方传入可用的集数下标。
     */
    fun selectSource(
        session: PlaybackSession,
        sourceIndex: Int,
        episodeIndex: Int = session.episodeIndex,
    ): PlaybackSession = session.copy(
        sourceIndex = sourceIndex,
        episodeIndex = episodeIndex,
        failedTargets = emptySet(),
        phase = PlaybackPhase.Idle,
    )

    /**
     * 试看闸门（、:1029-1046）。
     *
     * 只有正在播放时才判定；未跨越时长返回原状态，跨越则进 [PlaybackPhase.Blocked]。
     */
    fun gate(
        session: PlaybackSession,
        positionMs: Long,
        access: TrySeeAccess,
        trySeeMinutes: Int,
    ): PlaybackSession {
        val playing = session.phase as? PlaybackPhase.Playing ?: return session
        val blocked = access != TrySeeAccess.FREE &&
            TrySeeRules.isExceeded(positionMs, trySeeMinutes)
        return if (blocked) {
            session.copy(phase = PlaybackPhase.Blocked(access = access, lastUrl = playing.url))
        } else {
            session
        }
    }
}

/** 一次播放会话。不可变，所有迁移返回新实例。 */
data class PlaybackSession(
    val sourceIndex: Int = 0,
    val episodeIndex: Int = 0,
    val parseTargets: List<String> = emptyList(),
    val playerInfo: PlayerInfo? = null,
    val failedTargets: Set<Int> = emptySet(),
    val phase: PlaybackPhase = PlaybackPhase.Idle,
)

sealed interface PlaybackPhase {

    /** 初始状态：还没开始解析。 */
    data object Idle : PlaybackPhase

    /** 解析中；`pendingTargets` 是还没回结果、也没超时的线路下标集合。 */
    data class Parsing(val pendingTargets: Set<Int>) : PlaybackPhase

    /**
     * 已拿到可用地址。`headers` 来自 `issethead` 命中时的片源配置，再并上解析站
     * 下发的 Referer（片源显式配过则以片源为准）；两者都没有时为空。
     *
     * [audioUrl] 非空表示音视频分离的双流（B 站 DASH 直链），播放器需要合成两路；
     * [streamType] 是服务端声明的类型（dash/m3u8/mp4），只用于展示与判定。
     */
    data class Playing(
        val url: String,
        val targetIndex: Int,
        val headers: Map<String, String>,
        val audioUrl: String? = null,
        val streamType: String = "",
        /** 官方弹幕地址，解析站给了才有。 */
        val danmuUrl: String? = null,
    ) : PlaybackPhase

    /** 试看/付费闸门命中，等待升级或购买。 */
    data class Blocked(val access: TrySeeAccess, val lastUrl: String) : PlaybackPhase

    /** 所有线路都失败。 */
    data class Failed(val triedTargets: Int) : PlaybackPhase
}

private fun PlaybackPhase.pendingTargetsOrEmpty(): Set<Int> =
    (this as? PlaybackPhase.Parsing)?.pendingTargets.orEmpty()

/** Referer 请求头的标准写法；片源显式配置与解析站下发都用这个键。 */
private const val HEADER_REFERER = "Referer"
