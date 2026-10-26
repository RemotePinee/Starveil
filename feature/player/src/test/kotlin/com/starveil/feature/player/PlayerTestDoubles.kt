package com.starveil.feature.player

import com.starveil.core.common.AppResult
import com.starveil.core.model.PlayStream
import com.starveil.core.model.PlaySource
import com.starveil.core.model.PlayUrl
import com.starveil.core.model.PlayerInfo
import com.starveil.core.model.Vod
import kotlinx.coroutines.awaitCancellation

/**
 * 按目标线路依次返回预设结果；用尽后返回 null（模拟这条线路再也没回地址）。
 *
 * 只用于 JVM 测试，不碰 WebView / ExoPlayer。
 */
class ScriptedProbe(
    private val script: Map<Int, List<String?>>,
    /** 某条线路附带音轨时，模拟 B 站双流；键为目标线路下标。 */
    private val audio: Map<Int, String?> = emptyMap(),
    /** 某条线路返回官方弹幕地址（解析站 `/json` 的 `danmu`）；键为目标线路下标。 */
    private val danmu: Map<Int, String> = emptyMap(),
) : LineProbe {

    private val taken = mutableMapOf<Int, Int>()

    val calls = mutableListOf<Int>()

    val targets = mutableListOf<String>()

    override suspend fun probe(targetIndex: Int, target: String, vodUrl: String): PlayStream? {
        calls += targetIndex
        targets += target
        val index = taken.getOrDefault(targetIndex, 0)
        taken[targetIndex] = index + 1
        val url = script[targetIndex]?.getOrNull(index) ?: return null
        val audioUrl = audio[targetIndex]
        return PlayStream(
            url = url,
            audioUrl = audioUrl,
            type = if (audioUrl != null) "dash" else "",
            danmuUrl = danmu[targetIndex],
        )
    }
}

/** 永不返回的探测，用于 8 秒超时用例；被取消才结束，用来验证"超时后不再重试"。 */
class HangingProbe : LineProbe {

    val calls = mutableListOf<Int>()

    override suspend fun probe(targetIndex: Int, target: String, vodUrl: String): PlayStream? {
        calls += targetIndex
        awaitCancellation()
    }
}

/** 记录每次上报；fake 里不做任何 HTTP（AGENTS.md 第 4 节：feature 不发请求）。 */
class RecordingProgressSink : ProgressSink {

    data class Report(val sourceIndex: Int, val episodeIndex: Int, val seconds: Long)

    val reports = mutableListOf<Report>()

    override suspend fun reportWatchedSeconds(
        sourceIndex: Int,
        episodeIndex: Int,
        seconds: Long,
    ): AppResult<Unit> {
        reports += Report(sourceIndex, episodeIndex, seconds)
        return AppResult.Success(Unit)
    }
}

/** 可推进的假时钟，测试里显式指定每一刻。 */
class FakeClock(private var current: Long = 0L) : PlayerClock {

    override fun nowMs(): Long = current

    fun advanceTo(value: Long) {
        current = value
    }
}

/** 两个播放源：源 0 有两条解析线路、两集；源 1 只有一条线路、一集。 */
fun sampleVod(): Vod = Vod(
    id = 7,
    name = "示例影片",
    poster = null,
    remarks = null,
    year = null,
    area = null,
    score = null,
    blurb = null,
    playSources = listOf(
        PlaySource(
            index = 0,
            name = "高清",
            episodes = listOf(
                PlayUrl(episodeNumber = 1, title = "第 1 集", url = "vod-1-1"),
                PlayUrl(episodeNumber = 2, title = "第 2 集", url = "vod-1-2"),
            ),
            playerInfo = PlayerInfo(
                parseTargets = listOf("parse-0-a", "parse-0-b"),
                directLinkPatterns = emptyList(),
                headerPatterns = emptyList(),
                headers = emptyMap(),
            ),
        ),
        PlaySource(
            index = 1,
            name = "备用",
            episodes = listOf(PlayUrl(episodeNumber = 1, title = "第 1 集", url = "vod-2-1")),
            playerInfo = PlayerInfo(
                parseTargets = listOf("parse-1-a"),
                directLinkPatterns = emptyList(),
                headerPatterns = emptyList(),
                headers = emptyMap(),
            ),
        ),
    ),
)
