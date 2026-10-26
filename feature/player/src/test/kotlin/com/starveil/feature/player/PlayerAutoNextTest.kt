package com.starveil.feature.player

import com.starveil.core.model.PlaybackPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 自动下一集。
 *
 * 播完一集 → 自动接下一集并重新解析；播到最后一集 → 停在末帧，不回绕、不重播。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerAutoNextTest {

    fun `playback end moves to the next episode`() = runTest {
        val viewModel = PlayerViewModel(
            vod = sampleVod(),
            deps = PlayerDeps(
                probe = ScriptedProbe(
                    mapOf(
                        0 to listOf(
                            "https://cdn.example.com/1.m3u8",
                            "https://cdn.example.com/2.m3u8",
                        ),
                    ),
                ),
                progressSink = RecordingProgressSink(),
                clock = FakeClock(),
                scope = this,
            ),
        )
        viewModel.start()
        advanceUntilIdle()
        assertEquals(0, viewModel.state.value.episodeIndex)

        viewModel.onPlaybackEnded()
        advanceUntilIdle()

        // 换到第 2 集并重新进入播放态
        val state = viewModel.state.value
        assertEquals(1, state.episodeIndex)
        assertTrue(state.phase is PlaybackPhase.Playing)
    }

    @Test
    fun `last episode stops instead of replaying`() = runTest {
        val viewModel = PlayerViewModel(
            vod = sampleVod(),
            deps = PlayerDeps(
                probe = ScriptedProbe(mapOf(0 to listOf("https://cdn.example.com/1.m3u8"))),
                progressSink = RecordingProgressSink(),
                clock = FakeClock(),
                scope = this,
            ),
        )
        viewModel.start()
        advanceUntilIdle()

        // 直接跳到最后一集（示例影片第 1 源只有两集）
        viewModel.onEpisodeSelected(1)
        advanceUntilIdle()
        viewModel.onPlaybackEnded()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(1, state.episodeIndex)
        assertTrue(!state.isPlaying)
    }
}
