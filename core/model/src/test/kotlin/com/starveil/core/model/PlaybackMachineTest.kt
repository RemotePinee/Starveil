package com.starveil.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放状态机。断言对齐 与 。
 */
class PlaybackMachineTest {

    private fun session(failed: Set<Int> = emptySet(), playerInfo: PlayerInfo? = null) = PlaybackSession(
        parseTargets = listOf("https://p1.example.com/?url=", "https://p2.example.com/?url="),
        playerInfo = playerInfo,
        failedTargets = failed,
    )

    @Test
    fun `begin parse probes all healthy lines concurrently`() {
        val phase = PlaybackMachine.beginParse(session()).phase

        assertEquals(PlaybackPhase.Parsing(pendingTargets = setOf(0, 1)), phase)
    }

    @Test
    fun `begin parse skips lines that already failed`() {
        val phase = PlaybackMachine.beginParse(session(failed = setOf(0))).phase

        assertEquals(PlaybackPhase.Parsing(pendingTargets = setOf(1)), phase)
    }

    @Test
    fun `begin parse fails fast when every line already failed`() {
        val phase = PlaybackMachine.beginParse(session(failed = setOf(0, 1))).phase

        assertEquals(PlaybackPhase.Failed(triedTargets = 2), phase)
    }

    @Test
    fun `first successful line wins and its url is normalized`() {
        val next = PlaybackMachine.onParsed(session(), targetIndex = 1, stream = PlayStream("//cdn.example.com/1.m3u8"))

        val playing = next.phase as PlaybackPhase.Playing
        assertEquals("https://cdn.example.com/1.m3u8", playing.url)
        assertEquals(1, playing.targetIndex)
        assertTrue(playing.headers.isEmpty())
    }

    @Test
    fun `empty result marks that line failed instead of retrying it forever`() {
        // 拿到空地址只是换下一条，不记失败，会反复打同一条线路
        val next = PlaybackMachine.onParsed(session(), targetIndex = 0, stream = PlayStream("  "))

        assertEquals(setOf(0), next.failedTargets)
        assertEquals(PlaybackPhase.Parsing(pendingTargets = setOf(1)), next.phase)
    }

    @Test
    fun `line failure narrows the pending set`() {
        val parsing = PlaybackMachine.beginParse(session())

        val next = PlaybackMachine.recordFailure(parsing, targetIndex = 0)

        assertEquals(setOf(0), next.failedTargets)
        assertEquals(PlaybackPhase.Parsing(pendingTargets = setOf(1)), next.phase)
    }

    @Test
    fun `last line failure ends in failed phase`() {
        val parsing = PlaybackMachine.beginParse(session(failed = setOf(0)))

        val next = PlaybackMachine.recordFailure(parsing, targetIndex = 1)

        assertEquals(PlaybackPhase.Failed(triedTargets = 2), next.phase)
    }

    @Test
    fun `timeout marks every in-flight line failed`() {
        val parsing = PlaybackMachine.beginParse(session())

        val next = PlaybackMachine.onTimeout(parsing)

        assertEquals(setOf(0, 1), next.failedTargets)
        assertEquals(PlaybackPhase.Failed(triedTargets = 2), next.phase)
    }

    @Test
    fun `timeout on idle session does nothing`() {
        val idle = session()

        assertEquals(idle, PlaybackMachine.onTimeout(idle))
    }

    @Test
    fun `selecting an episode resets failure memory`() {
        val playing = PlaybackMachine.onParsed(session(), 0, PlayStream("https://cdn.example.com/1.m3u8"))

        val next = PlaybackMachine.selectEpisode(playing, episodeIndex = 3)

        assertEquals(3, next.episodeIndex)
        assertTrue(next.failedTargets.isEmpty())
        assertEquals(PlaybackPhase.Idle, next.phase)
    }

    @Test
    fun `selecting a source resets failures and keeps episode unless overridden`() {
        val next = PlaybackMachine.selectSource(
            PlaybackMachine.onParsed(session(), 0, PlayStream("https://cdn.example.com/1.m3u8")),
            sourceIndex = 2,
        )

        assertEquals(2, next.sourceIndex)
        assertEquals(0, next.episodeIndex)
        assertEquals(PlaybackPhase.Idle, next.phase)
    }

    @Test
    fun `headers are attached only when url matches issethead patterns`() {
        val info = PlayerInfo(
            parseTargets = emptyList(),
            directLinkPatterns = emptyList(),
            headerPatterns = listOf(".*cdn\\.example\\.com.*"),
            headers = mapOf("Referer" to "https://example.com/"),
        )

        val matched = PlaybackMachine.onParsed(
            session(playerInfo = info),
            0,
            PlayStream("https://cdn.example.com/a.m3u8"),
        )
        val unmatched = PlaybackMachine.onParsed(
            session(playerInfo = info),
            0,
            PlayStream("https://other.example.com/a.m3u8"),
        )

        assertEquals("https://example.com/", (matched.phase as PlaybackPhase.Playing).headers["Referer"])
        assertTrue((unmatched.phase as PlaybackPhase.Playing).headers.isEmpty())
    }

    @Test
    fun `stream referer becomes the playing Referer header`() {
        val stream = PlayStream(
            url = "https://cdn.example.com/a.m3u8",
            referer = "https://www.bilibili.com/",
        )

        val playing = PlaybackMachine.onParsed(session(), 0, stream).phase as PlaybackPhase.Playing

        assertEquals("https://www.bilibili.com/", playing.headers["Referer"])
    }

    @Test
    fun `explicit player info Referer wins over the stream referer`() {
        val info = PlayerInfo(
            parseTargets = emptyList(),
            directLinkPatterns = emptyList(),
            headerPatterns = listOf(".*cdn\\.example\\.com.*"),
            headers = mapOf("Referer" to "https://example.com/"),
        )
        val stream = PlayStream(
            url = "https://cdn.example.com/a.m3u8",
            referer = "https://www.bilibili.com/",
        )

        val playing = PlaybackMachine.onParsed(session(playerInfo = info), 0, stream).phase
            as PlaybackPhase.Playing

        assertEquals("https://example.com/", playing.headers["Referer"])
    }

    @Test
    fun `dual stream keeps audio url and accepts dash type without mpd suffix`() {
        // B 站 relay 地址没有 .mp4/.m3u8 后缀，只能靠 type = dash 判定
        val stream = PlayStream(
            url = "https://relay.example.com/stream?p=bilibili&url=video",
            audioUrl = "https://relay.example.com/stream?p=bilibili&url=audio",
            type = "dash",
        )

        val playing = PlaybackMachine.onParsed(session(), 0, stream).phase as PlaybackPhase.Playing

        assertEquals(stream.url, playing.url)
        assertEquals(stream.audioUrl, playing.audioUrl)
        assertEquals("dash", playing.streamType)
    }

    @Test
    fun `broken audio url degrades to single stream instead of failing the line`() {
        val playing = PlaybackMachine.onParsed(
            session(),
            0,
            PlayStream("https://cdn.example.com/1.mp4", audioUrl = "   ", type = "dash"),
        ).phase as PlaybackPhase.Playing

        assertNull(playing.audioUrl)
        assertEquals("https://cdn.example.com/1.mp4", playing.url)
    }

    @Test
    fun `trysee gate blocks only after the free window is exceeded`() {
        val playing = PlaybackMachine.onParsed(session(), 0, PlayStream("https://cdn.example.com/1.m3u8"))

        val before = PlaybackMachine.gate(
            playing,
            positionMs = 1_000,
            access = TrySeeAccess.NEED_VIP,
            trySeeMinutes = 6,
        )
        val after = PlaybackMachine.gate(
            playing,
            positionMs = 6 * 60_000L + 1,
            access = TrySeeAccess.NEED_VIP,
            trySeeMinutes = 6,
        )

        assertTrue(before.phase is PlaybackPhase.Playing)
        assertEquals(
            PlaybackPhase.Blocked(access = TrySeeAccess.NEED_VIP, lastUrl = "https://cdn.example.com/1.m3u8"),
            after.phase,
        )
    }

    @Test
    fun `free access never blocks`() {
        val playing = PlaybackMachine.onParsed(session(), 0, PlayStream("https://cdn.example.com/1.m3u8"))

        val next = PlaybackMachine.gate(playing, positionMs = 10_000_000, access = TrySeeAccess.FREE, trySeeMinutes = 1)

        assertTrue(next.phase is PlaybackPhase.Playing)
        assertFalse(next.phase is PlaybackPhase.Blocked)
    }

    @Test
    fun `gate ignores sessions that are not playing`() {
        val idle = session()

        assertEquals(
            idle,
            PlaybackMachine.gate(idle, positionMs = 10_000_000, access = TrySeeAccess.NEED_PAY, trySeeMinutes = 1),
        )
    }

    @Test
    fun `paywall blocks before probing when there is no free window`() {
        // 付费墙打开时服务端把地址全置空：拦截必须发生在探测之前，不能靠空地址失败兜底
        val next = PlaybackMachine.blockBeforePlay(session(), TrySeeAccess.NEED_VIP, trySeeMinutes = 0)

        assertEquals(PlaybackPhase.Blocked(access = TrySeeAccess.NEED_VIP, lastUrl = ""), next.phase)
    }

    @Test
    fun `paywall leaves free access and open trials on the normal path`() {
        val free = PlaybackMachine.blockBeforePlay(session(), TrySeeAccess.FREE, trySeeMinutes = 0)
        val trial = PlaybackMachine.blockBeforePlay(session(), TrySeeAccess.NEED_PAY, trySeeMinutes = 3)

        assertEquals(PlaybackPhase.Idle, free.phase)
        assertEquals(PlaybackPhase.Idle, trial.phase)
    }
}
