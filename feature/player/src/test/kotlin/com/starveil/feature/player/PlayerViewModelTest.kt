package com.starveil.feature.player

import app.cash.turbine.test
import com.starveil.core.model.PlaybackMachine
import com.starveil.core.model.PlaybackPhase
import com.starveil.core.model.ProgressRules
import com.starveil.core.model.TrySeeAccess
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放状态机的接线测试（JVM 层，不启动 Android、不碰 ExoPlayer / WebView）。
 *
 * 覆盖 PlaybackMachine 的三条关键语义：先回先赢、全败才 Failed、超时按失败处理。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    @Test
    fun `pause ad is eligible only after an explicit user pause`() = runTest {
        val viewModel = playerViewModel(ScriptedProbe(mapOf(0 to listOf("https://cdn.example.com/a.m3u8"))))
        viewModel.start()
        advanceUntilIdle()

        viewModel.onPlayPauseClick()
        assertTrue(viewModel.state.value.isPauseAdEligible)

        viewModel.pausePlayback()
        assertFalse(viewModel.state.value.isPauseAdEligible)

        viewModel.onPlayPauseClick()
        assertTrue(viewModel.state.value.isPlaying)
        assertFalse(viewModel.state.value.isPauseAdEligible)

        viewModel.onPlayPauseClick()
        assertTrue(viewModel.state.value.isPauseAdEligible)
    }

    @Test
    fun `first line success enters playing with that line`() = runTest {
        val probe = ScriptedProbe(mapOf(0 to listOf("https://cdn.example.com/a.m3u8")))
        val viewModel = playerViewModel(probe)

        viewModel.start()
        advanceUntilIdle()

        assertEquals(0, viewModel.state.value.lineIndex)
        assertEquals("https://cdn.example.com/a.m3u8", viewModel.state.value.url)
        assertEquals("parse-0-a", probe.targets.first())
        assertTrue(viewModel.state.value.phase is PlaybackPhase.Playing)
    }

    @Test
    fun `dual stream line exposes audio url to the player surface`() = runTest {
        val probe = ScriptedProbe(
            script = mapOf(0 to listOf("https://relay.example.com/stream?p=bilibili&url=video")),
            audio = mapOf(0 to "https://relay.example.com/stream?p=bilibili&url=audio"),
        )
        val viewModel = playerViewModel(probe)

        viewModel.start()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("https://relay.example.com/stream?p=bilibili&url=video", state.url)
        assertEquals("https://relay.example.com/stream?p=bilibili&url=audio", state.audioUrl)
        assertTrue(state.phase is PlaybackPhase.Playing)
    }

    @Test
    fun `single stream line leaves audio url empty`() = runTest {
        val probe = ScriptedProbe(mapOf(0 to listOf("https://cdn.example.com/a.m3u8")))
        val viewModel = playerViewModel(probe)

        viewModel.start()
        advanceUntilIdle()

        assertEquals(null, viewModel.state.value.audioUrl)
    }

    @Test
    fun `all lines failing ends in failed phase with message`() = runTest {
        val probe = ScriptedProbe(mapOf(0 to listOf(null), 1 to listOf(null)))
        val viewModel = playerViewModel(probe)

        viewModel.start()
        advanceUntilIdle()

        val phase = viewModel.state.value.phase
        assertTrue(phase is PlaybackPhase.Failed)
        assertEquals(2, (phase as PlaybackPhase.Failed).triedTargets)
        assertNotNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun `timeout fails every pending line without retrying it`() = runTest {
        val probe = HangingProbe()
        val viewModel = playerViewModel(probe)

        viewModel.start()
        advanceTimeBy(PlaybackMachine.LINE_TIMEOUT_MS + 1)
        runCurrent()

        assertTrue(viewModel.state.value.phase is PlaybackPhase.Failed)
        // 历史版本超时后仍重试同一条线路，这里每条线路只探测一次
        assertEquals(listOf(0, 1), probe.calls.sorted())
    }

    @Test
    fun `switching line records failure and probes the next one`() = runTest {
        val probe = ScriptedProbe(
            mapOf(
                0 to listOf("https://cdn.example.com/b.m3u8"),
                1 to listOf("https://cdn.example.com/c.m3u8"),
            ),
        )
        val viewModel = playerViewModel(probe)

        viewModel.start()
        advanceUntilIdle()
        assertEquals(0, viewModel.state.value.lineIndex)

        viewModel.onSwitchLineClick()
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.lineIndex)
        assertEquals(1, probe.calls.count { it == 0 })
    }

    @Test
    fun `selecting another episode clears the failed set`() = runTest {
        val probe = ScriptedProbe(
            mapOf(
                0 to listOf(null, "https://cdn.example.com/ep2.m3u8"),
                1 to listOf(null),
            ),
        )
        val viewModel = playerViewModel(probe)

        viewModel.start()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.phase is PlaybackPhase.Failed)

        viewModel.onEpisodeSelected(1)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.phase is PlaybackPhase.Playing)
        assertEquals(1, viewModel.state.value.episodeIndex)
        assertEquals(2, probe.calls.count { it == 0 })
    }

    @Test
    fun `try see gate blocks playback and emits upgrade event`() = runTest {
        val probe = ScriptedProbe(mapOf(0 to listOf("https://cdn.example.com/d.m3u8")))
        val viewModel = playerViewModel(probe, access = TrySeeAccess.NEED_VIP, trySeeMinutes = 3)

        viewModel.start()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.onProgress(positionMs = 181_000L, durationMs = 600_000L, isPlaying = true)
            assertEquals(TrySeeAccess.NEED_VIP, (awaitItem() as PlayerEvent.TrySeeBlocked).access)
        }

        assertTrue(viewModel.state.value.phase is PlaybackPhase.Blocked)
        assertNotNull(viewModel.state.value.trySeeNotice)
    }

    @Test
    fun `progress is reported once per thirty second period`() = runTest {
        val clock = FakeClock()
        val sink = RecordingProgressSink()
        val probe = ScriptedProbe(mapOf(0 to listOf("https://cdn.example.com/e.m3u8")))
        val viewModel = playerViewModel(probe, sink = sink, clock = clock)

        viewModel.start()
        advanceUntilIdle()

        listOf(10_000L, 20_000L, 30_000L).forEach { moment ->
            clock.advanceTo(moment)
            viewModel.onProgress(positionMs = moment, durationMs = 600_000L, isPlaying = true)
        }
        advanceUntilIdle()

        assertEquals(listOf(ProgressRules.REPORT_INTERVAL_MS / 1_000), sink.reports.map { it.seconds })
        assertEquals(0, sink.reports.single().episodeIndex)
    }
}

/** 用 TestScope 当作注入作用域，虚拟时间可控（PlayerDeps.scope）。 */
private fun TestScope.playerViewModel(
    probe: LineProbe,
    sink: ProgressSink = RecordingProgressSink(),
    clock: PlayerClock = FakeClock(),
    access: TrySeeAccess = TrySeeAccess.FREE,
    trySeeMinutes: Int = 0,
): PlayerViewModel = PlayerViewModel(
    vod = sampleVod(),
    deps = PlayerDeps(
        probe = probe,
        progressSink = sink,
        clock = clock,
        scope = this,
        trySeeAccess = access,
        trySeeMinutes = trySeeMinutes,
    ),
)

/** 倍速档位是弹层与循环切换共用的唯一来源，档位表漂移会让选中项高亮错位。 */
class PlayerSpeedsTest {

    @Test
    fun `steps match the plan list and include 0_75`() {
        assertEquals(listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f), PlayerSpeeds.STEPS)
    }

    @Test
    fun `next wraps around the step list`() {
        assertEquals(1.0f, PlayerSpeeds.next(0.75f), DELTA)
        assertEquals(0.75f, PlayerSpeeds.next(2.0f), DELTA)
        assertEquals(0.75f, PlayerSpeeds.next(1.37f), DELTA)
    }

    private companion object {
        const val DELTA = 0.0001f
    }
}
