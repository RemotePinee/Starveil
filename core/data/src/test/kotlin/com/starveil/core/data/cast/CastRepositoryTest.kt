package com.starveil.core.data.cast

import com.starveil.core.model.CastDevice
import com.starveil.core.model.CastPlaybackState
import com.starveil.core.model.CastSession
import com.starveil.core.model.CastUiStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话状态机：真机不可用时，用假 engine 把"搜索 → 投 → 轮询 → 掉线 → 断开"整条走一遍。
 *
 * 这些分支在真机上要么很难复现（电视中途拔电），要么代价很高（每次都要去客厅按遥控器），
 * 所以必须在这里测。协议层的收发另有 [LanHttpTest] 与 [DlnaSoapTest]。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CastRepositoryTest {

    private val device = CastDevice(
        id = "uuid:renderer-1",
        name = "客厅电视",
        host = "192.168.1.20",
        controlUrl = "http://192.168.1.20:49152/avt",
    )

    @Test
    fun `search publishes devices`() = runTest {
        val engine = FakeEngine(devices = listOf(device))
        val repository = DlnaCastRepository(backgroundScope, engine)

        repository.search()

        assertEquals(CastUiStatus.Devices(listOf(device)), repository.status.value)
    }

    @Test
    fun `successful cast exposes connected session`() = runTest {
        val engine = FakeEngine(devices = listOf(device))
        val repository = DlnaCastRepository(backgroundScope, engine)

        assertTrue(repository.castTo(device, "https://cdn.example.com/a.m3u8", "第一集"))

        val connected = repository.status.value as CastUiStatus.Connected
        assertEquals(device, connected.session.device)
        assertEquals(CastPlaybackState.Playing, connected.session.state)
        assertEquals(device, repository.currentDevice())
    }

    @Test
    fun `rejected cast fails with a readable reason`() = runTest {
        val engine = FakeEngine(devices = listOf(device)).apply { accept = false }
        val repository = DlnaCastRepository(backgroundScope, engine)

        assertEquals(false, repository.castTo(device, "https://cdn.example.com/a.m3u8", "第一集"))
        assertTrue(repository.status.value is CastUiStatus.Failed)
    }

    @Test
    fun `session is marked lost after two failed polls`() = runTest {
        val engine = FakeEngine(devices = listOf(device))
        val repository = DlnaCastRepository(backgroundScope, engine)
        repository.castTo(device, "https://cdn.example.com/a.m3u8", "第一集")

        engine.reachable = false
        advanceTimeBy(5_001)
        assertTrue(repository.status.value is CastUiStatus.Connected)
        advanceTimeBy(5_001)

        assertTrue(repository.status.value is CastUiStatus.Failed)
        assertEquals(null, repository.currentDevice())
    }

    @Test
    fun `poll updates position while playing`() = runTest {
        val engine = FakeEngine(devices = listOf(device))
        val repository = DlnaCastRepository(backgroundScope, engine)
        repository.castTo(device, "https://cdn.example.com/a.m3u8", "第一集")

        engine.positionMs = 42_000
        advanceTimeBy(5_001)

        val connected = repository.status.value as CastUiStatus.Connected
        assertEquals(42_000L, connected.session.positionMs)
    }

    @Test
    fun `disconnect stops playback and returns the last position`() = runTest {
        val engine = FakeEngine(devices = listOf(device))
        val repository = DlnaCastRepository(backgroundScope, engine)
        repository.castTo(device, "https://cdn.example.com/a.m3u8", "第一集")
        engine.positionMs = 90_000

        assertEquals(90_000L, repository.disconnect())
        assertEquals(CastUiStatus.Idle, repository.status.value)
        assertEquals(1, engine.stopCalls)
    }

    @Test
    fun `disconnect without a session is a no-op`() = runTest {
        val repository = DlnaCastRepository(backgroundScope, FakeEngine(emptyList()))

        assertEquals(0L, repository.disconnect())
        assertEquals(CastUiStatus.Idle, repository.status.value)
    }

    private class FakeEngine(devices: List<CastDevice>) : CastEngine {
        private val devices = devices
        var accept = true
        var reachable = true
        var positionMs = 0L
        var stopCalls = 0

        override suspend fun discover(timeoutMs: Long): List<CastDevice> = devices

        override suspend fun cast(device: CastDevice, url: String, title: String): CastAttempt =
            if (accept) CastAttempt.Ok else CastAttempt.Failed("电视拒绝了这次投屏")

        override suspend fun play(device: CastDevice): Boolean = true

        override suspend fun pause(device: CastDevice): Boolean = true

        override suspend fun stop(device: CastDevice): Boolean {
            stopCalls++
            return true
        }

        override suspend fun readSession(device: CastDevice): CastSession = if (reachable) {
            CastSession(device = device, state = CastPlaybackState.Playing, positionMs = positionMs)
        } else {
            CastSession(device = device, state = CastPlaybackState.Error)
        }
    }
}
