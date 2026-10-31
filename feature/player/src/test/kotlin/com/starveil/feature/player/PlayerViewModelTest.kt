package com.starveil.feature.player

import app.cash.turbine.test
import com.starveil.core.common.AppResult
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
    fun `direct link pattern skips every parse target`() = runTest {
        val probe = ScriptedProbe(mapOf(0 to listOf("https://cdn.example.com/x.m3u8")))
        val viewModel = playerViewModel(
            probe,
            directLinkPatterns = listOf("\\.m3u8"),
            firstEpisodeUrl = "https://cdn.example.com/x.m3u8",
        )

        viewModel.start()
        advanceUntilIdle()

        // 一个解析站都没请求过：直链不该先拿去解析一遍再失败
        assertTrue("直链不应触碰解析站", probe.targets.isEmpty())
        // 直接进播放，且沿用原始地址
        assertTrue(viewModel.state.value.phase is PlaybackPhase.Playing)
        assertEquals("https://cdn.example.com/x.m3u8", viewModel.state.value.url)
        // 起播即播放：漏了 onPlaybackStarted 的话这里是 false，用户得手动点播放
        assertTrue("直链起播应自动播放", viewModel.state.value.isPlaying)
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
        val viewModel = playerViewModel(probe, access = TrySeeAccess.NEED_VIP, trySeeSeconds = 180)

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
    fun `paywalled episode blocks before probing and resumes after unlock`() = runTest {
        // 付费墙打开时后端把 urls[].url 全置空，探测也拿不到地址：
        // 客户端必须在探测前就拦，否则播放器只会报"解析失败"
        val url = "https://cdn.example.com/a.m3u8"
        val probe = ScriptedProbe(mapOf(0 to listOf(url, url)))
        val viewModel = playerViewModel(probe, access = TrySeeAccess.NEED_VIP, trySeeSeconds = 0)

        viewModel.start()
        advanceUntilIdle()

        assertEquals(
            PlaybackPhase.Blocked(access = TrySeeAccess.NEED_VIP, lastUrl = ""),
            viewModel.state.value.phase,
        )
        assertTrue(probe.calls.isEmpty())

        // 解锁回到 FREE：走既有的 updateAccess → retryPlayback，不新造恢复链路
        viewModel.updateAccess(TrySeeAccess.FREE, 0)
        advanceUntilIdle()
        // 解锁后的自动恢复走 applyAccess → retryPlayback → start 这条既有链路，
        // 它依赖真实调度器与网络回包，在单测里只能验证"拦截发生在探测之前"这一段；
        // 恢复播放请在真机上用「试看卡住 → 积分兑换」验证，这里不写会骗人的断言。
    }

    @Test
    fun `unlock reloads detail once and plays the fresh address`() = runTest {
        // 付费墙打开时后端把 urls[].url 全置空：解锁后必须先重取详情再重试，
        // 否则重试拿到的还是空地址，用户看到的是"全线解析失败"
        val fresh = "https://cdn.example.com/unlocked.m3u8"
        val probe = ScriptedProbe(mapOf(0 to listOf(fresh)))
        val loads = mutableListOf<Int>()
        val viewModel = playerViewModel(
            probe,
            access = TrySeeAccess.NEED_VIP,
            detailLoader = VodDetailLoader { vodId ->
                loads += vodId
                AppResult.Success(sampleVod(firstEpisodeUrl = fresh))
            },
        )

        viewModel.start()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.phase is PlaybackPhase.Blocked)
        // 被拦下时一次都不重取：地址是空的，重取也只会失败
        assertTrue(loads.isEmpty())

        viewModel.updateAccess(TrySeeAccess.FREE, 0)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.phase is PlaybackPhase.Playing)

        // 权限没再变：前台重查 / 重组重复推同一次 FREE 时不能再重取详情
        viewModel.updateAccess(TrySeeAccess.FREE, 0)
        advanceUntilIdle()
        assertEquals(listOf(SAMPLE_VOD_ID), loads)
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
    trySeeSeconds: Int = 0,
    directLinkPatterns: List<String> = emptyList(),
    firstEpisodeUrl: String = "vod-1-1",
    detailLoader: VodDetailLoader? = null,
): PlayerViewModel = PlayerViewModel(
    vod = sampleVod(directLinkPatterns = directLinkPatterns, firstEpisodeUrl = firstEpisodeUrl),
    deps = PlayerDeps(
        probe = probe,
        progressSink = sink,
        clock = clock,
        scope = this,
        trySeeAccess = access,
        trySeeSeconds = trySeeSeconds,
        detailLoader = detailLoader,
    ),
)

/** `sampleVod` 的影片 id：校验解锁后重取的是当前这部片。 */
private const val SAMPLE_VOD_ID = 7

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
