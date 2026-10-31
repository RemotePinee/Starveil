package com.starveil.feature.player

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.data.VodRepository
import com.starveil.core.model.TrySeeAccess
import com.starveil.core.model.TrySeeGate
import com.starveil.core.model.Vod
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 竖屏试看收尾：预览播完后进正片、返回时保留当集与闸门、失败重试不重播缓存地址。
 *
 * 夹具（`verticalViewModel` / `playerDeps` / `shortPlayVod`）与主用例同包共用，
 * 见 VerticalPlayViewModelTest.kt。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VerticalPreviewCompletionTest {
    @Test
    fun `preview completion opens series then advances and keeps the new episode on return`() = runTest {
        val viewModel = verticalViewModel()
        advanceUntilIdle()
        viewModel.onProgress(90_000L, 90_000L, isPlaying = true)
        var opened = 0
        viewModel.onPreviewPlaybackEnded { episode, position ->
            opened += 1
            assertEquals(1, episode)
            assertEquals(90_000L, position)
            assertFalse(viewModel.state.value.isPlaying)
            assertTrue(viewModel.beginInlineSeries(episode))
        }
        assertEquals(1, opened)
        assertEquals(2, viewModel.state.value.current?.number)
        assertEquals(0L, viewModel.state.value.positionMs)
        assertTrue(viewModel.state.value.isPlaying)
        assertTrue(viewModel.endInlineSeries())
        assertEquals(2, viewModel.state.value.current?.number)
        assertEquals(0L, viewModel.state.value.positionMs)
        assertTrue(viewModel.state.value.isPlaying)
        viewModel.onPlayPauseClick()
        viewModel.onPreviewPlaybackEnded { _, _ -> opened += 1 }
        assertEquals(1, opened)
        assertFalse(viewModel.state.value.isPlaying)
    }

    @Test
    fun `last blocked and unsuccessful preview entry never advances silently`() = runTest {
        val last = verticalViewModel(episodeNumber = 3)
        val blocked = verticalViewModel(deps = playerDeps(trySeeAccess = TrySeeAccess.NEED_PAY, trySeeSeconds = 60))
        val unavailable = verticalViewModel()
        advanceUntilIdle()
        blocked.onProgress(61_000L, 90_000L, isPlaying = true)
        var opened = 0
        last.onPreviewPlaybackEnded { _, _ -> opened += 1 }
        blocked.onPreviewPlaybackEnded { _, _ -> opened += 1 }
        assertEquals(0, opened)
        unavailable.onPreviewPlaybackEnded { _, _ -> opened += 1 }
        assertEquals(1, opened)
        assertEquals(1, unavailable.state.value.current?.number)
        assertFalse(unavailable.state.value.isPlaying)
        assertFalse(last.state.value.isPlaying)
        assertTrue(blocked.state.value.isBlocked)
    }

    @Test
    fun `inline return keeps the paused episode with its own gate`() = runTest {
        val viewModel = verticalViewModel()
        advanceUntilIdle()
        viewModel.onProgress(20_000L, 90_000L, isPlaying = true)
        assertTrue(viewModel.beginInlineSeries(1))
        viewModel.onPageChanged(1)
        viewModel.updateTrySee(2, TrySeeGate(TrySeeAccess.NEED_PAY, 60))
        viewModel.onProgress(45_000L, 90_000L, isPlaying = true)
        viewModel.onPlayPauseClick()
        val paused = viewModel.state.value
        assertEquals(2, paused.current?.number)
        assertEquals(45_000L, paused.positionMs)
        assertTrue(viewModel.endInlineSeries())
        assertEquals(paused, viewModel.state.value)
        viewModel.onProgress(61_000L, 90_000L, isPlaying = true)
        assertTrue(viewModel.state.value.isBlocked)
    }

    @Test
    fun `cold entry keeps metadata through pending detail failure and retry without playing cached urls`() = runTest {
        val cached = shortPlayVod()
        val preview = com.starveil.core.model.ShortPlay(
            VOD_ID, cached.name, cached.poster, 3, 0, "短剧分类", "短剧简介",
        )
        for (fromFeed in listOf(false, true)) {
            val pending = CompletableDeferred<AppResult<Vod>>()
            val repository = object : VodRepository by FakeVodRepository(AppResult.Success(cached)) {
                override fun cachedDetail(vodId: Int): Vod? = cached.takeUnless { fromFeed }
                override suspend fun detail(vodId: Int): AppResult<Vod> = pending.await()
            }
            val viewModel = VerticalPlayViewModel(
                VOD_ID, repository, playerDeps(), initialMetadata = preview.takeIf { fromFeed },
            )
            fun assertMetadata() {
                assertEquals(cached.name, viewModel.state.value.title)
                assertEquals(cached.poster, viewModel.state.value.cover)
                assertEquals(3, viewModel.state.value.episodeCount)
                assertNull(viewModel.state.value.playingUrl)
                assertTrue(viewModel.state.value.episodes.isEmpty())
            }
            assertMetadata()
            assertTrue(viewModel.state.value.isLoading)
            pending.complete(AppResult.Failure(AppError.Network(null)))
            advanceUntilIdle()
            assertMetadata()
            assertFalse(viewModel.state.value.isLoading)
            assertEquals("网络异常，无法加载剧集", viewModel.state.value.errorMessage)
            viewModel.retry()
            assertMetadata()
            assertTrue(viewModel.state.value.isLoading)
            advanceUntilIdle()
        }
    }
}
