package com.starveil.feature.player

import app.cash.turbine.test
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.model.PlaybackPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DURATION_MS = 600_000L

/**
 * 弹幕接线：窗口预取、窗口去重、拉空推进、失败降级与发送。
 *
 * 全部走 ViewModel 公开接口，不直接给 DanmuController 打桩。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerDanmuTest {

    @Test
    fun `first window is loaded once`() = runTest {
        val source = FakeDanmuSource(mapOf(0L to listOf(fakeDanmu(1, 0L))))
        val viewModel = startedViewModel(source)

        viewModel.onProgress(0L, DURATION_MS, true)
        advanceUntilIdle()

        // 整集只拉一次：atTimeMs 固定 0，坐标是 CMS 弹幕表的三元组
        assertEquals(listOf(0L), source.loads.map { it.atTimeMs })
        assertEquals(7, source.loads.single().target.vodId)
        assertEquals(0, source.loads.single().target.sid)
        assertEquals(0, source.loads.single().target.nid)
        assertEquals(listOf("弹幕1"), viewModel.danmu.value.map { it.text })
    }

    @Test
    fun `whole episode is loaded once even when position advances`() = runTest {
        val source = FakeDanmuSource(
            mapOf(
                0L to listOf(fakeDanmu(1, 0L), fakeDanmu(2, 10_500L)),
            ),
        )
        val viewModel = startedViewModel(source)

        viewModel.onProgress(0L, DURATION_MS, true)
        advanceUntilIdle()
        viewModel.onProgress(10_000L, DURATION_MS, true)
        advanceUntilIdle()

        // CMS 弹幕接口一次给整集：只请求一次，列表里是整集两条
        assertEquals(1, source.loads.size)
        assertEquals(listOf("弹幕1", "弹幕2"), viewModel.danmu.value.map { it.text })
    }

    @Test
    fun `same window is not loaded twice`() = runTest {
        val source = FakeDanmuSource(mapOf(0L to listOf(fakeDanmu(1, 0L))))
        val viewModel = startedViewModel(source)

        listOf(0L, 500L, 4_000L, 9_500L).forEach { viewModel.onProgress(it, DURATION_MS, true) }
        advanceUntilIdle()

        assertEquals(1, source.loads.size)
    }

    @Test
    fun `load failure keeps playing and shows nothing`() = runTest {
        val source = FakeDanmuSource().apply { loadFailure = AppError.Network(null) }
        val viewModel = startedViewModel(source)

        viewModel.onProgress(0L, DURATION_MS, true)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.phase is PlaybackPhase.Playing)
        assertNotNull(viewModel.state.value.url)
        assertTrue(viewModel.danmu.value.isEmpty())
    }

    @Test
    fun `seeking back keeps the already loaded episode danmu`() = runTest {
        val source = FakeDanmuSource(mapOf(0L to listOf(fakeDanmu(1, 0L))))
        val viewModel = startedViewModel(source)

        viewModel.onProgress(20_000L, DURATION_MS, true)
        advanceUntilIdle()
        viewModel.onSeekTo(0L)
        advanceUntilIdle()

        // 倒着拖不会重新请求，也不会把已加载的弹幕清空
        assertEquals(1, source.loads.size)
        assertTrue(viewModel.danmu.value.isNotEmpty())
    }

    @Test
    fun `official danmu is fetched once and merged with user danmu`() = runTest {
        val source = FakeDanmuSource(mapOf(0L to listOf(fakeDanmu(1, 0L))))
        source.officialItems = listOf(fakeDanmu(100, 2_000L, content = "官方弹幕"))
        val viewModel = startedViewModel(
            source,
            probe = ScriptedProbe(
                script = mapOf(0 to listOf("https://cdn.example.com/danmu.m3u8")),
                danmu = mapOf(0 to OFFICIAL_DANMU_URL),
            ),
        )

        viewModel.onProgress(0L, DURATION_MS, true)
        advanceUntilIdle()
        viewModel.onProgress(1_000L, DURATION_MS, true)
        advanceUntilIdle()

        // 同一地址只拉一次；官方弹幕与用户弹幕同处一个时间轴
        assertEquals(1, source.officialRequests.size)
        assertTrue(viewModel.danmu.value.any { it.text == "官方弹幕" })
    }

    @Test
    fun `simultaneous danmu keep their own timestamps`() = runTest {
        val source = FakeDanmuSource(
            mapOf(
                0L to List(6) { index -> fakeDanmu(index + 1, 0L, content = "同秒$index") },
            ),
        )
        val viewModel = startedViewModel(source)

        viewModel.onProgress(0L, DURATION_MS, true)
        advanceUntilIdle()

        // 控制器只负责时间轴与来源：不排轨、不丢条（排轨在绘制层按真实字宽算）
        assertEquals(6, viewModel.danmu.value.size)
        assertTrue(viewModel.danmu.value.all { it.atTimeMs == 0L })
        assertTrue(viewModel.danmu.value.all { it.priority == DanmuItem.PRIORITY_USER })
    }

    @Test
    fun `send success puts the danmu on screen immediately`() = runTest {
        val source = FakeDanmuSource()
        val viewModel = startedViewModel(source)
        viewModel.onProgress(12_000L, DURATION_MS, true)
        advanceUntilIdle()

        viewModel.sendDanmu("  你好  ")
        advanceUntilIdle()

        assertEquals("你好", source.sends.single().content)
        assertEquals(12_000L, source.sends.single().atTimeMs)
        assertEquals(listOf("你好"), viewModel.danmu.value.map { it.text })
    }

    @Test
    fun `send failure emits one-off event`() = runTest {
        val source = FakeDanmuSource().apply {
            sendResult = AppResult.Failure(AppError.Business(code = 400, message = "内容不合法"))
        }
        val viewModel = startedViewModel(source)

        viewModel.events.test {
            viewModel.sendDanmu("hi")
            advanceUntilIdle()
            assertTrue(awaitItem() is PlayerEvent.DanmuFailed)
        }
        assertTrue(viewModel.danmu.value.isEmpty())
    }
}

/** 用 TestScope 当注入作用域，虚拟时间可控；起播后再返回，避免探测与弹幕互相干扰。 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.startedViewModel(
    source: DanmuSource?,
    probe: LineProbe = ScriptedProbe(mapOf(0 to listOf("https://cdn.example.com/danmu.m3u8"))),
): PlayerViewModel {
    val viewModel = PlayerViewModel(
        vod = sampleVod(),
        deps = PlayerDeps(
            probe = probe,
            progressSink = RecordingProgressSink(),
            clock = FakeClock(),
            scope = this,
            danmuSource = source,
        ),
    )
    viewModel.start()
    advanceUntilIdle()
    return viewModel
}

private const val OFFICIAL_DANMU_URL = "https://danmu.example.com/danmu?url=x"
