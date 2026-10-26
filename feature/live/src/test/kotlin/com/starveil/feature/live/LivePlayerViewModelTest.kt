package com.starveil.feature.live

import app.cash.turbine.test
import com.starveil.core.common.liveErrorMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Rule
import org.junit.Test

/**
 * 选台与重试测试。
 *
 * 历史版本点卡片直接开 X5，应用侧没有"选中哪个台"的状态。
 * 守三条：未选不播、选台进缓冲、错误后重试回到缓冲。
 */
class LivePlayerViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    @Test
    fun `idle before any channel is selected`() {
        val viewModel = LivePlayerViewModel()

        Assert.assertNull(viewModel.state.value.channel)
        Assert.assertEquals(LivePlaybackState.Idle, viewModel.state.value.playback)
    }

    @Test
    fun `selection emits buffering`() = runTest {
        val viewModel = LivePlayerViewModel()

        viewModel.state.test {
            Assert.assertEquals(LivePlaybackState.Idle, awaitItem().playback)

            viewModel.select(channel(id = 7))

            val state = awaitItem()
            Assert.assertEquals(7L, state.channel?.id)
            Assert.assertEquals(LivePlaybackState.Buffering, state.playback)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `playing event moves state to playing`() {
        val viewModel = LivePlayerViewModel()
        viewModel.select(channel())

        viewModel.onPlaying()

        Assert.assertEquals(LivePlaybackState.Playing, viewModel.state.value.playback)
    }

    @Test
    fun `playback error then retry returns to buffering`() {
        val viewModel = LivePlayerViewModel()
        viewModel.select(channel())
        val attemptBefore = viewModel.state.value.attempt

        // 播放器给的是已经翻好的人话（LivePlayer 用 liveErrorMessage 映射过），
        // VM 只负责落状态，不要二次包装成"播放失败（…）"
        viewModel.onError(liveErrorMessage("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"))
        val failed = viewModel.state.value.playback
        Assert.assertTrue(failed is LivePlaybackState.Error)
        val message = (failed as LivePlaybackState.Error).message
        Assert.assertTrue(message.contains("连不上"))
        Assert.assertFalse(message.contains("ERROR_CODE"))

        viewModel.retry()

        Assert.assertEquals(LivePlaybackState.Buffering, viewModel.state.value.playback)
        Assert.assertEquals(attemptBefore + 1, viewModel.state.value.attempt)
    }

    @Test
    fun `http status is spelled out instead of the media3 constant`() {
        val viewModel = LivePlayerViewModel()
        viewModel.select(channel())

        viewModel.onError(liveErrorMessage("ERROR_CODE_IO_BAD_HTTP_STATUS", 403))
        val forbidden = (viewModel.state.value.playback as LivePlaybackState.Error).message
        Assert.assertTrue(forbidden.contains("403"))
        Assert.assertTrue(forbidden.contains("拒绝"))

        viewModel.onError(liveErrorMessage("ERROR_CODE_IO_BAD_HTTP_STATUS", 404))
        val missing = (viewModel.state.value.playback as LivePlaybackState.Error).message
        Assert.assertTrue(missing.contains("404"))
        Assert.assertTrue(missing.contains("失效"))
    }

    @Test
    fun `channel without url reports error instead of buffering`() {
        val viewModel = LivePlayerViewModel()

        viewModel.select(channel(id = 6, url = null))

        val state = viewModel.state.value
        Assert.assertEquals(6L, state.channel?.id)
        Assert.assertTrue(state.playback is LivePlaybackState.Error)
    }

    @Test
    fun `retry without selection keeps idle`() {
        val viewModel = LivePlayerViewModel()

        viewModel.retry()

        Assert.assertEquals(LivePlaybackState.Idle, viewModel.state.value.playback)
    }

    @Test
    fun `playback events before selection are ignored`() {
        val viewModel = LivePlayerViewModel()

        viewModel.onPlaying()
        viewModel.onError("boom")

        Assert.assertEquals(LivePlaybackState.Idle, viewModel.state.value.playback)
    }
}
