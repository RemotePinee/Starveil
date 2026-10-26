package com.starveil.feature.player

import com.starveil.core.model.PlaybackPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断点续播：起播后要把服务端读回的位置交给播放器 seek 一次。
 *
 * 只钉 ViewModel 这一环（起播 → pendingSeekMs）；上游（仓库读进度、详情页传参）
 * 各有自己的测试，出问题时能直接区分是哪一层。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerResumeTest {

    @Test
    fun `resume position becomes a pending seek on playback start`() = runTest {
        val viewModel = PlayerViewModel(
            vod = sampleVod(),
            deps = PlayerDeps(
                probe = ScriptedProbe(mapOf(0 to listOf("https://cdn.example.com/r.m3u8"))),
                progressSink = RecordingProgressSink(),
                clock = FakeClock(),
                scope = this,
            ),
            resumePositionMs = 60_000L,
        )

        viewModel.start()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.phase is PlaybackPhase.Playing)
        assertEquals(60_000L, viewModel.state.value.pendingSeekMs)
        assertEquals(60_000L, viewModel.state.value.positionMs)
    }

    @Test
    fun `without resume playback starts from zero`() = runTest {
        val viewModel = PlayerViewModel(
            vod = sampleVod(),
            deps = PlayerDeps(
                probe = ScriptedProbe(mapOf(0 to listOf("https://cdn.example.com/z.m3u8"))),
                progressSink = RecordingProgressSink(),
                clock = FakeClock(),
                scope = this,
            ),
        )

        viewModel.start()
        advanceUntilIdle()

        assertEquals(0L, viewModel.state.value.pendingSeekMs)
    }
}
