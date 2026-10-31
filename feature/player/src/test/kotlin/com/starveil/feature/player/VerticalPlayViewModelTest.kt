package com.starveil.feature.player

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.data.VodRepository
import com.starveil.core.model.PlaySource
import com.starveil.core.model.PlayUrl
import com.starveil.core.model.PlayerInfo
import com.starveil.core.model.TrySeeAccess
import com.starveil.core.model.TrySeeGate
import com.starveil.core.model.Vod
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 竖屏播放状态持有者的 JVM 测试（不启动 Android、不碰 ExoPlayer / 网络）。
 *
 * 覆盖计划 §5 P3 的四类行为：起始集、翻页切集、进度上报、失败与试看闸门。
 * 播放器本身不在这里测——它由 Compose 层的 `VerticalEngineEffects` 推流，
 * 媒体装载逻辑已有横屏页的测试覆盖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VerticalPlayViewModelTest {

    @Test
    fun `starts on the episode from the route`() = runTest {
        val viewModel = verticalViewModel(episodeNumber = 2)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertEquals(1, state.currentIndex)
        assertEquals(2, state.current?.number)
        assertEquals("https://cdn.example.com/dj-2.m3u8", state.playingUrl)
        assertTrue(state.isPlaying)
    }

    @Test
    fun `unknown episode number falls back to the first episode`() = runTest {
        val viewModel = verticalViewModel(episodeNumber = 99)
        advanceUntilIdle()

        assertEquals(0, viewModel.state.value.currentIndex)
        assertEquals(3, viewModel.state.value.episodes.size)
    }

    @Test
    fun `pager swipe switches url and resets position`() = runTest {
        val viewModel = verticalViewModel()
        advanceUntilIdle()
        viewModel.onProgress(positionMs = 12_000, durationMs = 90_000, isPlaying = true)

        viewModel.onPageChanged(2)

        val state = viewModel.state.value
        assertEquals(2, state.currentIndex)
        assertEquals(3, state.current?.number)
        assertEquals("https://cdn.example.com/dj-3.m3u8", state.playingUrl)
        assertEquals(0L, state.positionMs)
        assertEquals(0L, state.durationMs)
    }

    @Test
    fun `page beyond the last episode is clamped`() = runTest {
        val viewModel = verticalViewModel()
        advanceUntilIdle()

        viewModel.onPageChanged(99)

        assertEquals(2, viewModel.state.value.currentIndex)
    }

    @Test
    fun `repeated callback for the current page keeps the position`() = runTest {
        val viewModel = verticalViewModel()
        advanceUntilIdle()
        viewModel.onProgress(positionMs = 5_000, durationMs = 90_000, isPlaying = true)

        viewModel.onPageChanged(0)

        assertEquals(5_000L, viewModel.state.value.positionMs)
        assertEquals(0, viewModel.state.value.currentIndex)
    }

    @Test
    fun `detail failure surfaces the error message`() = runTest {
        val viewModel = verticalViewModel(result = AppResult.Failure(AppError.Network(null)))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertTrue(state.episodes.isEmpty())
        assertEquals("网络异常，无法加载剧集", state.errorMessage)
        assertNull(state.playingUrl)
    }

    @Test
    fun `vod without episodes surfaces the empty message`() = runTest {
        val viewModel = verticalViewModel(result = AppResult.Success(shortPlayVod(episodeCount = 0)))
        advanceUntilIdle()

        assertEquals(MSG_NO_EPISODE, viewModel.state.value.errorMessage)
        assertFalse(viewModel.state.value.isLoading)
    }

    @Test
    fun `watched seconds are reported through the existing sink`() = runTest {
        val clock = FakeClock()
        val sink = RecordingProgressSink()
        val viewModel = verticalViewModel(deps = playerDeps(clock = clock, sink = sink))
        advanceUntilIdle()

        clock.advanceTo(30_000)
        viewModel.onProgress(positionMs = 30_000, durationMs = 90_000, isPlaying = true)
        advanceUntilIdle()

        assertEquals(1, sink.reports.size)
        assertEquals(30L, sink.reports.first().seconds)
        assertEquals(VERTICAL_PLAY_SOURCE_INDEX, sink.reports.first().sourceIndex)
        assertEquals(0, sink.reports.first().episodeIndex)
    }

    @Test
    fun `try see gate stops playback once the minutes are used up`() = runTest {
        val viewModel = verticalViewModel(
            deps = playerDeps(trySeeAccess = TrySeeAccess.NEED_VIP, trySeeMinutes = 1),
        )
        advanceUntilIdle()

        viewModel.onProgress(positionMs = 61_000, durationMs = 90_000, isPlaying = true)

        assertTrue(viewModel.state.value.isBlocked)
        assertEquals(TrySeeAccess.NEED_VIP, viewModel.state.value.blockedAccess)
        assertFalse(viewModel.state.value.isPlaying)
    }

    @Test
    fun `withheld episode address shows unlock layer and plays after unlock`() = runTest {
        val viewModel = verticalViewModel()
        advanceUntilIdle()
        // 付费墙打开时后端把 url 置空：这时客户端只可能落到"没有可播放地址"的故障文案
        viewModel.stateFlow.value = viewModel.state.value.copy(
            playingUrl = null,
            isPlaying = false,
            errorMessage = MSG_NO_PLAY_URL,
        )

        viewModel.updateTrySee(1, TrySeeGate(TrySeeAccess.NEED_PAY, 0))

        // 权限一到就该是"需要解锁"，故障文案必须让位
        assertTrue(viewModel.state.value.isBlocked)
        assertNull(viewModel.state.value.errorMessage)

        viewModel.updateTrySee(1, TrySeeGate(TrySeeAccess.FREE, 0))

        assertEquals("https://cdn.example.com/dj-1.m3u8", viewModel.state.value.playingUrl)
        assertNull(viewModel.state.value.blockedAccess)
        assertTrue(viewModel.state.value.isPlaying)
    }

    @Test
    fun `play pause is ignored while blocked`() = runTest {
        val viewModel = verticalViewModel(
            deps = playerDeps(trySeeAccess = TrySeeAccess.NEED_PAY, trySeeMinutes = 1),
        )
        advanceUntilIdle()
        viewModel.onProgress(positionMs = 61_000, durationMs = 90_000, isPlaying = true)

        viewModel.onPlayPauseClick()

        assertFalse(viewModel.state.value.isPlaying)
    }

    @Test
    fun `playback ended rolls over to the next episode`() = runTest {
        val viewModel = verticalViewModel()
        advanceUntilIdle()

        viewModel.onPlaybackEnded()

        val state = viewModel.state.value
        assertEquals(1, state.currentIndex)
        assertEquals("https://cdn.example.com/dj-2.m3u8", state.playingUrl)
        assertTrue(state.isPlaying)
        assertEquals(0L, state.positionMs)
    }

    @Test
    fun `playback ended on the last episode stops instead of wrapping`() = runTest {
        val viewModel = verticalViewModel(episodeNumber = 3)
        advanceUntilIdle()

        viewModel.onPlaybackEnded()

        assertEquals(2, viewModel.state.value.currentIndex)
        assertFalse(viewModel.state.value.isPlaying)
    }

    @Test
    fun `playback ended while blocked keeps the current episode`() = runTest {
        val viewModel = verticalViewModel(
            deps = playerDeps(trySeeAccess = TrySeeAccess.NEED_VIP, trySeeMinutes = 1),
        )
        advanceUntilIdle()
        viewModel.onProgress(positionMs = 61_000, durationMs = 90_000, isPlaying = true)

        viewModel.onPlaybackEnded()

        assertEquals(0, viewModel.state.value.currentIndex)
        assertFalse(viewModel.state.value.isPlaying)
    }

    @Test
    fun `next episode url is prefetched and reused on swipe`() = runTest {
        val viewModel = verticalViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.preloader.contains(1))

        viewModel.onPageChanged(1)

        assertEquals(1, viewModel.preloader.hits)
        assertEquals("https://cdn.example.com/dj-2.m3u8", viewModel.state.value.playingUrl)
    }

    @Test
    fun `retry drops the preloaded urls`() = runTest {
        val viewModel = verticalViewModel()
        advanceUntilIdle()
        viewModel.onPageChanged(1)
        assertTrue(viewModel.preloader.contains(2))

        viewModel.retry()
        advanceUntilIdle()

        assertFalse(viewModel.preloader.contains(2))
        assertTrue(viewModel.preloader.contains(1))
    }

    @Test
    fun `video tap toggles playback both ways`() = runTest {
        val viewModel = verticalViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isPlaying)

        viewModel.onPlayPauseClick()
        assertFalse(viewModel.state.value.isPlaying)

        viewModel.onPlayPauseClick()
        assertTrue(viewModel.state.value.isPlaying)
    }

    @Test
    fun `danmu stays off for short play by default`() = runTest {
        val source = FakeDanmuSource()
        val viewModel = verticalViewModel(deps = playerDeps(danmuSource = source))
        advanceUntilIdle()

        viewModel.onProgress(positionMs = 1_000, durationMs = 90_000, isPlaying = true)
        advanceUntilIdle()

        assertTrue(source.loads.isEmpty())
        assertTrue(viewModel.danmu.value.isEmpty())
    }

    @Test
    fun `enabling danmu feeds the controller with the vod key`() = runTest {
        val source = FakeDanmuSource()
        val viewModel = verticalViewModel(deps = playerDeps(danmuSource = source), danmuEnabled = true)
        advanceUntilIdle()

        viewModel.onProgress(positionMs = 1_000, durationMs = 90_000, isPlaying = true)
        advanceUntilIdle()

        // 短剧：一条源，坐标为 (vodId, VERTICAL_PLAY_SOURCE_INDEX, 当前集下标)
        assertEquals(VOD_ID, source.loads.first().target.vodId)
        assertEquals(VERTICAL_PLAY_SOURCE_INDEX, source.loads.first().target.sid)
    }
}

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
        val blocked = verticalViewModel(deps = playerDeps(trySeeAccess = TrySeeAccess.NEED_PAY, trySeeMinutes = 1))
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
        viewModel.updateTrySee(2, TrySeeGate(TrySeeAccess.NEED_PAY, 1))
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

/** 短剧只有一条源、多集直链，fake 里不需要探测线路。 */
private fun shortPlayVod(episodeCount: Int = 3): Vod = Vod(
    id = VOD_ID,
    name = "测试短剧",
    poster = "https://img.example.com/poster.jpg",
    remarks = "全 $episodeCount 集",
    year = null,
    area = null,
    score = null,
    blurb = null,
    playSources = listOf(
        PlaySource(
            index = 0,
            name = "直链",
            episodes = (1..episodeCount).map { number ->
                PlayUrl(
                    episodeNumber = number,
                    title = "第 $number 集",
                    url = "https://cdn.example.com/dj-$number.m3u8",
                )
            },
            playerInfo = PlayerInfo(
                parseTargets = emptyList(),
                directLinkPatterns = emptyList(),
                headerPatterns = emptyList(),
                headers = emptyMap(),
            ),
        ),
    ),
)

private const val VOD_ID = 30

private fun TestScope.playerDeps(
    clock: FakeClock = FakeClock(),
    sink: RecordingProgressSink = RecordingProgressSink(),
    trySeeAccess: TrySeeAccess = TrySeeAccess.FREE,
    trySeeMinutes: Int = 0,
    danmuSource: DanmuSource? = null,
): PlayerDeps = PlayerDeps(
    probe = ScriptedProbe(emptyMap()),
    progressSink = sink,
    clock = clock,
    scope = this,
    trySeeAccess = trySeeAccess,
    trySeeMinutes = trySeeMinutes,
    danmuSource = danmuSource,
)

private fun TestScope.verticalViewModel(
    result: AppResult<Vod> = AppResult.Success(shortPlayVod()),
    deps: PlayerDeps = playerDeps(),
    episodeNumber: Int = FIRST_EPISODE_NUMBER,
    danmuEnabled: Boolean = false,
): VerticalPlayViewModel = VerticalPlayViewModel(
    vodId = VOD_ID,
    vodRepository = FakeVodRepository(result),
    deps = deps,
    initialEpisodeNumber = episodeNumber,
    danmuEnabled = danmuEnabled,
)

/** 只实现详情：竖屏页的取数入口只有这一个。 */
private class FakeVodRepository(private val result: AppResult<Vod>) : VodRepository {

    override suspend fun list(type: Int, page: Int, limit: Int): AppResult<List<Vod>> =
        AppResult.Success(emptyList())

    override suspend fun detail(vodId: Int): AppResult<Vod> = result
}
