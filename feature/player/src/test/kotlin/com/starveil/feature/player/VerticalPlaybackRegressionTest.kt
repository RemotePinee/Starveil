package com.starveil.feature.player

import app.cash.turbine.test
import com.starveil.core.common.AppResult
import com.starveil.core.data.VodRepository
import com.starveil.core.model.PlayUrl
import com.starveil.core.model.TrySeeAccess
import com.starveil.core.model.TrySeeGate
import com.starveil.core.model.Vod
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VerticalPlaybackRegressionTest {
    @Test
    fun `portrait and landscape video fit without stretching`() {
        assertEquals(VerticalVideoFrame(1080, 1920), fitVerticalVideo(1080, 2400, 9f / 16f))
        assertEquals(VerticalVideoFrame(1080, 607), fitVerticalVideo(1080, 1920, 16f / 9f))
    }
    @Test
    fun `media prepares seeks then plays and inactive suspension stops once`() {
        val (output, session) = regressionPlayback()
        val state = VerticalPlayUiState(playingUrl = "first", isPlaying = true, positionMs = 12_345L)
        session.update(state, active = true, speed = 1f)
        assertEquals(listOf("pause", "prepare:first", "seek:12345", "speed:1.0", "play"), output.calls)
        output.calls.clear()
        assertTrue(session.suspendPlayback())
        assertFalse(session.suspendPlayback())
        session.update(state, active = false, speed = 1f)
        assertEquals(listOf("pause", "pause"), output.calls)
        assertFalse(session.canObserve(state))
        assertFalse(session.canPlay(state))
        output.calls.clear()
        val seek = state.copy(positionMs = 20_000L, seekRevision = 1L)
        assertFalse(session.canObserve(seek))
        session.update(seek, active = true, speed = 1f)
        assertEquals(listOf("seek:20000", "speed:1.0", "play"), output.calls)
        assertTrue(session.canObserve(seek))
    }
    @Test
    fun `permission suspension and transient missing url preserve a manually paused prepared source`() {
        val (output, session) = regressionPlayback()
        val paused = VerticalPlayUiState(playingUrl = "first", isPlaying = false, positionMs = 12_345L)
        session.update(paused, active = false, speed = 1f)
        assertEquals(listOf("pause"), output.calls)
        assertFalse(session.isPrepared(paused))
        session.update(paused.copy(blockedAccess = TrySeeAccess.NEED_PAY), active = true, speed = 1f)
        assertFalse(session.isPrepared(paused))
        session.update(paused, active = true, speed = 1f)
        assertEquals(1, output.calls.count { it == "prepare:first" })
        output.calls.clear()
        session.suspendPlayback()
        session.update(paused.copy(playingUrl = null), active = false, speed = 1f)
        session.update(paused, active = true, speed = 1f)
        assertEquals(listOf("pause", "pause", "speed:1.0", "pause"), output.calls)
        assertTrue(session.isCurrent(paused))
        assertFalse(session.canPlay(paused))
        output.calls.clear()
        val blocked = paused.copy(blockedAccess = TrySeeAccess.NEED_PAY, positionMs = 61_000L, seekRevision = 1L)
        session.update(blocked, active = true, speed = 1f)
        assertEquals(listOf("pause"), output.calls)
        assertFalse(session.isCurrent(blocked))
        session.update(blocked.copy(blockedAccess = null), active = true, speed = 1f)
        assertEquals(listOf("pause", "seek:61000", "speed:1.0", "pause"), output.calls)
    }
    @Test
    fun `same url episode and retry prepare again but seek keeps the source`() {
        val (output, session) = regressionPlayback()
        val first = VerticalPlayUiState(playingUrl = "shared", isPlaying = true)
        session.update(first, active = true, speed = 1f)
        session.update(first.copy(positionMs = 500L, seekRevision = 1L), active = true, speed = 1f)
        assertEquals(1, output.calls.count { it == "prepare:shared" })
        val next = first.copy(currentIndex = 1)
        session.update(next, active = false, speed = 1f)
        assertFalse(session.isPrepared(next))
        assertEquals(1, output.calls.count { it == "prepare:shared" })
        session.update(next, active = true, speed = 1f)
        session.update(next.copy(playbackRevision = 1L), active = true, speed = 1f)
        assertEquals(3, output.calls.count { it == "prepare:shared" })
    }
    @Test
    fun `old frame or permission wait stays buffering and manual pause wins over buffering`() {
        val ready = VerticalPlayUiState(playingUrl = "first", isPlaying = true, isBuffering = false)
        assertFalse(verticalNeedsBuffering(ready, hasCurrentFrame = true, active = true))
        assertTrue(verticalNeedsBuffering(ready, hasCurrentFrame = false, active = true))
        assertTrue(verticalNeedsBuffering(ready, hasCurrentFrame = true, active = false))
        val paused = ready.copy(isPlaying = false, isBuffering = true)
        assertFalse(verticalNeedsBuffering(paused, hasCurrentFrame = false, active = true))
        val blocked = ready.copy(blockedAccess = TrySeeAccess.NEED_PAY)
        assertFalse(verticalNeedsBuffering(blocked, hasCurrentFrame = false, active = true))
    }
    @Test
    fun `preview completion pauses without switching episodes`() = runTest {
        val viewModel = regressionViewModel()
        viewModel.state.test {
            val ready = awaitItem()
            viewModel.onPlaybackEnded(preview = true)
            val ended = awaitItem()
            assertFalse(ended.isPlaying)
            assertEquals(ready.currentIndex, ended.currentIndex)
            assertEquals(ready.playingUrl, ended.playingUrl)
        }
    }
    @Test
    fun `initial resume belongs to the selected episode and is not reused after retry or switching`() = runTest {
        val viewModel = regressionViewModel(resumeMs = 12_345L, episodeNumber = 2)
        assertEquals(2, viewModel.state.value.current?.number)
        assertEquals(12_345L, viewModel.state.value.positionMs)
        viewModel.onProgress(27_000L, 90_000L, isPlaying = true)
        viewModel.onPlaybackError("media failed")
        viewModel.retry()
        assertEquals(27_000L, viewModel.state.value.positionMs)
        viewModel.onPageChanged(0)
        assertEquals(0L, viewModel.state.value.positionMs)
        viewModel.onPageChanged(1)
        assertEquals(2, viewModel.state.value.current?.number)
        assertEquals(0L, viewModel.state.value.positionMs)
        val revision = viewModel.state.value.playbackRevision
        viewModel.retry()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.playbackRevision > revision)
        assertEquals(0L, viewModel.state.value.positionMs)
    }
    @Test
    fun `current episode gates ignore stale results and keep progress, pause and same episode retry`() = runTest {
        val viewModel = regressionViewModel()
        val paid = TrySeeGate(TrySeeAccess.NEED_PAY, 0)
        viewModel.updateTrySee(1, paid)
        assertTrue(viewModel.state.value.isBlocked)
        viewModel.onPageChanged(1)
        viewModel.onProgress(20_000L, 90_000L, isPlaying = false)
        assertNull(viewModel.state.value.blockedAccess)
        val before = viewModel.state.value
        viewModel.updateTrySee(1, paid)
        assertEquals(before, viewModel.state.value)
        viewModel.updateTrySee(2, paid)
        assertEquals(TrySeeAccess.NEED_PAY, viewModel.state.value.blockedAccess)
        assertFalse(viewModel.state.value.isPlaying)
        assertTrue(viewModel.beginInlineSeries(2))
        viewModel.updateTrySee(2, TrySeeGate(TrySeeAccess.FREE, 0))
        assertNull(viewModel.state.value.blockedAccess)
        assertTrue(viewModel.state.value.isPlaying)
        assertEquals(20_000L, viewModel.state.value.positionMs)
        viewModel.onPlayPauseClick()
        viewModel.updateTrySee(2, TrySeeGate(TrySeeAccess.FREE, 0))
        assertFalse(viewModel.state.value.isPlaying)
        viewModel.onSeekTo(25_000L)
        viewModel.onPlaybackError("media failed")
        viewModel.retry()
        val retried = viewModel.state.value
        assertTrue(viewModel.endInlineSeries())
        assertEquals(retried, viewModel.state.value)
        viewModel.onProgress(20_000L, 90_000L, isPlaying = false)
        assertNull(viewModel.state.value.blockedAccess)
        viewModel.updateTrySee(2, TrySeeGate(TrySeeAccess.NEED_PAY, 60))
        val revision = viewModel.state.value.seekRevision
        viewModel.onSeekTo(61_000L)
        assertTrue(viewModel.state.value.isBlocked)
        assertFalse(viewModel.state.value.isPlaying)
        assertEquals(revision + 1L, viewModel.state.value.seekRevision)
    }
    @Test
    fun `pager waits for idle compensates a bounce and gives real gesture landings priority`() {
        val coordinator = VerticalPagerCoordinator(initialSettledPage = 2)
        assertEquals(VerticalPagerAction.None, coordinator.next(VerticalPagerSnapshot(3, 2, true)))
        assertEquals(VerticalPagerAction.ScrollToEpisode(3), coordinator.next(VerticalPagerSnapshot(3, 2, false)))
        val programLanding = VerticalPagerSnapshot(3, 3, false)
        coordinator.onProgramScrollFinished(3, programLanding)
        assertEquals(VerticalPagerAction.None, coordinator.next(programLanding))
        assertEquals(VerticalPagerAction.None, coordinator.next(VerticalPagerSnapshot(4, 3, true)))
        assertEquals(VerticalPagerAction.SelectEpisode(2), coordinator.next(VerticalPagerSnapshot(4, 2, false)))
        assertEquals(VerticalPagerAction.None, coordinator.next(VerticalPagerSnapshot(2, 2, false)))
        assertFalse(VerticalPagerSnapshot(2, 2, true).isPlaybackReady)
        assertFalse(VerticalPagerSnapshot(4, 2, false).isPlaybackReady)
        assertTrue(VerticalPagerSnapshot(2, 2, false).isPlaybackReady)
    }
}
class VerticalOutputFrameRegressionTest {
    @Test
    fun `late cleanup cannot clear a replacement presentation controller`() {
        val ownership = VerticalPlaybackController()
        val (old, current) = Any() to Any()
        ownership.claim(old)
        ownership.claim(current)
        ownership.clear(old)
        assertFalse(ownership.owns(old))
        assertTrue(ownership.owns(current))
        ownership.clear(current)
        assertFalse(ownership.owns(current))
    }
    @Test
    fun `one texture bound to a new view starts a new session and keeps only the historical frame`() {
        val frames = Media3OutputFrame()
        val (old, current) = Any() to Any()
        frames.bind(old)
        frames.rendered()
        assertTrue(frames.updated(old))
        assertTrue(frames.hasCurrentFrame)
        frames.detach(old)
        assertTrue(frames.hasFrame)
        assertFalse(frames.hasCurrentFrame)
        assertNull(frames.redrawPosition(12_345L, 60_000L, ended = false))
        frames.bind(old)
        assertTrue(frames.hasFrame)
        assertFalse(frames.hasCurrentFrame)
        assertEquals(12_345L, frames.redrawPosition(12_345L, 60_000L, ended = false))
        assertNull(frames.redrawPosition(12_345L, 60_000L, ended = false))
        frames.rendered()
        frames.updated(old)
        assertTrue(frames.hasCurrentFrame)
        frames.bind(current)
        assertFalse(frames.hasFrame)
        assertFalse(frames.unbind(old))
        assertEquals(12_345L, frames.redrawPosition(12_345L, 60_000L, ended = false))
        assertNull(frames.redrawPosition(12_345L, 60_000L, ended = false))
        frames.rendered()
        assertFalse(frames.hasFrame)
        assertFalse(frames.hasCurrentFrame)
        assertFalse(frames.updated(old))
        assertTrue(frames.updated(current))
        assertTrue(frames.hasCurrentFrame)
        assertTrue(frames.detach(current))
        assertTrue(frames.unbind(current))
        assertFalse(frames.hasFrame)
    }
    @Test
    fun `ended replacement output redraws actual last frame once and never seeks to duration`() {
        val frames = Media3OutputFrame()
        frames.bind(Any())
        assertEquals(59_960L, frames.redrawPosition(60_000L, 60_000L, true, 59_960L))
        assertNull(frames.redrawPosition(60_000L, 60_000L, true, 59_960L))
        frames.bind(Any())
        assertEquals(59_999L, frames.redrawPosition(60_000L, 60_000L, ended = true))
        frames.bind(Any())
        assertEquals(0L, frames.redrawPosition(-1L, 0L, ended = true))
    }
    @Test
    fun `paused navigation does not reprepare or seek when native surface is rebound`() {
        val (output, session) = regressionPlayback()
        val paused = VerticalPlayUiState(playingUrl = "first", isPlaying = false, positionMs = 60_000L)
        session.update(paused, active = true, speed = 1f)
        output.calls.clear()
        session.update(paused, active = false, speed = 1f)
        session.update(paused.copy(blockedAccess = TrySeeAccess.NEED_PAY), active = true, speed = 1f)
        session.update(paused, active = true, speed = 1f)
        assertEquals(listOf("pause", "pause", "speed:1.0", "pause"), output.calls)
        assertFalse(session.canPlay(paused))
    }
    @Test
    fun `new set before old destroy waits for callback return and ignores old clear`() {
        val handoff = Media3OutputHandoff<Any>()
        val (old, next) = Any() to Any()
        handoff.request(next)
        assertNull(handoff.next(old, true))
        assertFalse(handoff.clear(old))
        val ticket = handoff.detach()
        assertNull(handoff.next(null, true))
        assertTrue(handoff.completeDetach(ticket))
        assertNull(handoff.next(null, false))
        assertTrue(handoff.next(null, true) === next)
    }
    @Test
    fun `queued handoff resumes pending view but explicit Back wins over its late reattach`() {
        val handoff = Media3OutputHandoff<Any>()
        val (old, pending) = Any() to Any()
        handoff.request(old)
        handoff.present(old)
        handoff.request(pending)
        assertTrue(handoff.suspendPending(pending, old))
        assertTrue(handoff.target === old)
        assertTrue(handoff.resumePending(pending))
        assertFalse(handoff.suspendPending(old, old))
        val ticket = handoff.detach()
        handoff.request(old)
        assertTrue(handoff.completeDetach(ticket))
        assertTrue(handoff.next(null, true) === old)
        assertFalse(handoff.resumePending(pending))
        assertFalse(handoff.suspendPending(old, old))
    }
    @Test
    fun `release cancels queued target and repeated detach rejects stale completion`() {
        val handoff = Media3OutputHandoff<Any>()
        val view = Any()
        handoff.request(view)
        val first = handoff.detach()
        handoff.reset()
        assertTrue(handoff.completeDetach(first))
        assertNull(handoff.next(null, true))
        handoff.request(view)
        val second = handoff.detach()
        assertFalse(handoff.completeDetach(first))
        handoff.reset()
        handoff.request(view)
        assertNull(handoff.next(null, true))
        assertTrue(handoff.completeDetach(second))
        assertTrue(handoff.next(null, true) === view)
        val third = handoff.detach()
        assertTrue(handoff.clear(view))
        assertNull(handoff.next(null, true))
        assertTrue(handoff.completeDetach(third))
        assertNull(handoff.next(null, true))
    }
}
@OptIn(ExperimentalCoroutinesApi::class)
class VerticalPreviewHandoffRegressionTest {
    @Test
    fun `same episode handoff reuses detail source and seek while rejecting old page suspension`() = runTest {
        var details = 0
        val viewModel = regressionViewModel(onDetail = { details += 1 })
        viewModel.onProgress(12_345L, 90_000L, isPlaying = true)
        val preview = viewModel.state.value
        val (output, session) = regressionPlayback(viewModel::ownsPlayback)
        val old = viewModel.defaultPlaybackToken
        session.update(preview, true, 1f, old)
        output.calls.clear()
        val full = Any()
        assertTrue(viewModel.beginPreviewHandoff(full, 1))
        assertFalse(viewModel.ownsPlayback(old))
        assertFalse(viewModel.beginPreviewHandoff(Any(), 2))
        assertFalse(viewModel.endInlineSeries())
        session.update(viewModel.state.value, false, 1f, old)
        assertFalse(session.suspendPlayback(old))
        assertTrue(output.calls.isEmpty())
        session.update(viewModel.state.value, false, 1f, full)
        session.update(viewModel.state.value, true, 1f, full)
        assertTrue(viewModel.endPreviewHandoff(full))
        assertFalse(viewModel.ownsPlayback(old))
        session.update(viewModel.state.value, true, 1f, viewModel.defaultPlaybackToken)
        assertEquals(preview, viewModel.state.value)
        assertEquals(1, details)
        assertFalse(output.calls.any { it.startsWith("prepare:") || it.startsWith("seek:") })
        assertFalse(viewModel.endPreviewHandoff(full))
        output.calls.clear()
        session.update(preview, false, 1f, old)
        assertFalse(session.suspendPlayback(old))
        viewModel.updateTrySee(1, TrySeeGate(TrySeeAccess.NEED_PAY, 0), old)
        assertEquals(preview, viewModel.state.value)
        session.update(preview.copy(currentIndex = 1), true, 1f, full)
        assertFalse(session.suspendPlayback(full))
        assertTrue(output.calls.isEmpty())
    }
    @Test
    fun `different episode navigation return restores preview pause position and gate`() = runTest {
        val viewModel = regressionViewModel()
        viewModel.onProgress(20_000L, 90_000L, isPlaying = true)
        viewModel.updateTrySee(1, TrySeeGate(TrySeeAccess.NEED_PAY, 60))
        viewModel.onPlayPauseClick()
        val preview = viewModel.state.value
        val (output, session) = regressionPlayback(viewModel::ownsPlayback)
        session.update(preview, true, 1f, viewModel.defaultPlaybackToken)
        val full = Any()
        assertTrue(viewModel.beginPreviewHandoff(full, 2))
        assertEquals(2, viewModel.state.value.current?.number)
        val selected = viewModel.state.value
        viewModel.updateTrySee(2, TrySeeGate(TrySeeAccess.NEED_PAY, 0))
        assertEquals(selected, viewModel.state.value)
        viewModel.updateTrySee(2, TrySeeGate(TrySeeAccess.FREE, 0), full)
        session.update(viewModel.state.value, true, 1f, full)
        viewModel.onProgress(30_000L, 90_000L, isPlaying = true)
        assertTrue(viewModel.endPreviewHandoff(full))
        val restored = viewModel.state.value
        assertEquals(preview.copy(seekRevision = restored.seekRevision), restored)
        assertTrue(restored.seekRevision > preview.seekRevision)
        session.update(restored, true, 1f, viewModel.defaultPlaybackToken)
        assertEquals(3, output.calls.count { it.startsWith("prepare:") })
        viewModel.updateTrySee(1, TrySeeGate(TrySeeAccess.FREE, 0), full)
        assertEquals(restored, viewModel.state.value)
        viewModel.onProgress(61_000L, 90_000L, isPlaying = false)
        assertEquals(TrySeeAccess.NEED_PAY, viewModel.state.value.blockedAccess)
    }
    @Test
    fun `navigation return seeks while inline same source keeps progressing without media reset`() = runTest {
        for (inline in listOf(false, true)) {
            val viewModel = regressionViewModel(resumeMs = 10_000L)
            val (output, session) = regressionPlayback(viewModel::ownsPlayback)
            val previewToken = viewModel.defaultPlaybackToken
            val original = viewModel.state.value
            session.update(original, true, 1f, previewToken)
            assertTrue(output.calls.containsAll(listOf("pause", "prepare:${original.playingUrl}", "seek:10000")))
            output.calls.clear()
            val token = if (inline) previewToken else Any()
            assertTrue(if (inline) viewModel.beginInlineSeries(1) else viewModel.beginPreviewHandoff(token, 1))
            assertEquals(original, viewModel.state.value)
            assertFalse(viewModel.beginInlineSeries(2))
            if (inline) {
                assertFalse(viewModel.beginPreviewHandoff(Any(), 2))
                assertFalse(viewModel.endPreviewHandoff(previewToken))
            }
            session.update(viewModel.state.value, true, 1f, token)
            viewModel.onProgress(30_000L, 90_000L, isPlaying = true)
            val progressed = viewModel.state.value
            assertTrue(if (inline) viewModel.endInlineSeries() else viewModel.endPreviewHandoff(token))
            session.update(viewModel.state.value, true, 1f, viewModel.defaultPlaybackToken)
            assertEquals(if (inline) progressed else original.copy(seekRevision = 1L), viewModel.state.value)
            assertEquals(!inline, output.calls.contains("seek:10000"))
            assertFalse(output.calls.any { it.startsWith("prepare:") || it == "pause" })
            assertEquals(inline, previewToken === viewModel.defaultPlaybackToken)
        }
    }
}
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.regressionViewModel(
    resumeMs: Long = 0L,
    episodeNumber: Int = FIRST_EPISODE_NUMBER,
    onDetail: () -> Unit = {},
): VerticalPlayViewModel {
    val base = sampleVod()
    val episodes = (1..2).map { PlayUrl(it, "第 $it 集", "https://cdn.example.com/$it.m3u8") }
    val vod = base.copy(playSources = listOf(base.playSources.first().copy(episodes = episodes)))
    val repository = object : VodRepository {
        override suspend fun detail(vodId: Int) = AppResult.Success(vod).also { onDetail() }
        override suspend fun list(type: Int, page: Int, limit: Int) = AppResult.Success(emptyList<Vod>())
    }
    return VerticalPlayViewModel(vodId = vod.id, vodRepository = repository,
        deps = PlayerDeps(probe = ScriptedProbe(emptyMap()), progressSink = RecordingProgressSink(),
            clock = FakeClock(), scope = this),
        initialEpisodeNumber = episodeNumber, resumeMs = resumeMs,
    ).also { testScheduler.advanceUntilIdle() }
}
private fun regressionPlayback(isOwner: (Any) -> Boolean = { true }) =
    RegressionPlaybackOutput().let { it to VerticalPlaybackSession(it, isOwner) }
private class RegressionPlaybackOutput : VerticalPlaybackOutput {
    val calls = mutableListOf<String>()
    override fun prepare(source: VerticalMediaSource) { calls += "prepare:${source.url}" }
    override fun seekTo(positionMs: Long) { calls += "seek:$positionMs" }
    override fun play() { calls += "play" }
    override fun pause() { calls += "pause" }
    override fun recover(positionMs: Long) { calls += "recover:$positionMs" }
    override fun setSpeed(speed: Float) { calls += "speed:$speed" }
}
