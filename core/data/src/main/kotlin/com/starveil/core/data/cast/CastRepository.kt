package com.starveil.core.data.cast

import android.content.Context
import com.starveil.core.model.CAST_SEARCH_TIMEOUT_MS
import com.starveil.core.model.CastDevice
import com.starveil.core.model.CastPlaybackState
import com.starveil.core.model.CastSession
import com.starveil.core.model.CastUiStatus
import com.starveil.core.data.cast.relay.CastRelayServer
import com.starveil.core.data.cast.relay.M3u8Rewriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock

/**
 * 投屏会话。UI 只依赖这个接口，不认 DLNA——协议实现换成别家（或自研升级）时页面不动。
 *
 * 会话所有权是**进程级**的：投屏期间用户切页面、退到后台，会话都还在；
 * 由 `AppContainer` 持有唯一实例。页面只负责"发起/停止/读状态"。
 */
interface CastRepository {

    val status: StateFlow<CastUiStatus>

    /** 当前连着的设备；没连着为 null。 */
    fun currentDevice(): CastDevice?

    /** 搜索局域网设备，结果写回 [status] 的 [CastUiStatus.Devices]。 */
    suspend fun search()

    /** 投一条地址；返回 false 时 [status] 已经是 [CastUiStatus.Failed]。 */
    suspend fun castTo(device: CastDevice, url: String, title: String): Boolean

    suspend fun pause(): Boolean

    suspend fun resume(): Boolean

    /**
     * 断开投屏，返回电视端最后的位置（毫秒）。
     *
     * 调用方拿它把本地播放器 seek 回去——投屏期间本地是停的，不接这一步就会出现
     * "退投后从头播"。读不到位置时返回 0，调用方保持原进度即可。
     */
    suspend fun disconnect(): Long

    /**
     * 中转投屏：起本机代理并返回给电视用的地址（带 `u=` 参数）。
     *
     * 适合"电视自己拉不到"的源：需要 Referer/UA、地址带短时效签名、或需要客户端解密。
     * 返回 null 表示代理起不来（没有 Wi-Fi、端口被占），此时只能走直链或放弃。
     */
    suspend fun relayUrlFor(url: String, headers: Map<String, String> = emptyMap()): String?
}

internal class DlnaCastRepository(
    private val scope: CoroutineScope,
    private val engine: CastEngine,
    private val searchTimeoutMs: Long = CAST_SEARCH_TIMEOUT_MS,
) : CastRepository {

    private val _status = MutableStateFlow<CastUiStatus>(CastUiStatus.Idle)
    override val status: StateFlow<CastUiStatus> = _status.asStateFlow()

    private val commandLock = Mutex()
    private var session: CastSession? = null
    private var pollJob: Job? = null
    /** 中转投屏的本机代理；只在连上电视且走中转时存在。 */
    private var relay: CastRelayServer? = null


    override fun currentDevice(): CastDevice? = session?.device

    /**
     * 搜索与投屏指令走同一把锁：连点三次不能并发起三个 DatagramSocket 与三把
     * MulticastLock，真机上会把广播风暴放大成"搜到一堆重复设备"。
     */
    override suspend fun search(): Unit = commandLock.withLock {
        _status.value = CastUiStatus.Searching
        // 兜底超时：engine 一旦卡住（socket、IO 都可能在真实设备上挂起），
        // 界面不能永远停在"正在搜索"——搜索窗口之外再给 2 秒就必须出结果。
        val devices = withTimeoutOrNull(searchTimeoutMs + SEARCH_GUARD_MS) {
            engine.discover(searchTimeoutMs)
        }.orEmpty()
        _status.value = CastUiStatus.Devices(devices)
    }

    override suspend fun castTo(device: CastDevice, url: String, title: String): Boolean =
        commandLock.withLock {
            pollJob?.cancel()
            val accepted = engine.cast(device, url, title)
            if (accepted is CastAttempt.Failed) {
                // 原因来自电视的 UPnPError（见 DlnaCastEngine.faultReasonOf），原样给用户看
                _status.value = CastUiStatus.Failed(accepted.reason)
                return@withLock false
            }
            val connected = CastSession(device = device, state = CastPlaybackState.Playing)
            session = connected
            _status.value = CastUiStatus.Connected(connected)
            startPolling(device)
            true
        }

    override suspend fun pause(): Boolean = command(engine::pause)


    override suspend fun relayUrlFor(url: String, headers: Map<String, String>): String? =
        withContext(Dispatchers.IO) {
            // 每次中转都重建代理：换源时请求头会变，复用旧服务会带着旧 Referer 回源
            stopRelay()
            val server = CastRelayServer(headers)
            val base = server.start()
            if (base == null) {
                CastLog.debug { "relay start failed" }
            } else {
                relay = server
            }
            base?.let { "$it/cast/media?u=" + M3u8Rewriter.encode(url) }
        }

    override suspend fun resume(): Boolean = command(engine::play)

    override suspend fun disconnect(): Long = commandLock.withLock {
        pollJob?.cancel()
        val device = session?.device
        if (device == null) {
            _status.value = CastUiStatus.Idle
            return@withLock 0
        }
        // 先读最后一次进度再停：Stop 之后多数电视会把 RelTime 清零
        val position = engine.readSession(device).positionMs
        engine.stop(device)
        stopRelay()
        session = null
        _status.value = CastUiStatus.Idle
        position
    }

    /** 停掉中转代理；没有启动过就什么都不做。 */
    private fun stopRelay() {
        runCatching { relay?.stop() }
        relay = null
    }

    private suspend fun command(action: suspend (CastDevice) -> Boolean): Boolean = commandLock.withLock {
        val device = session?.device ?: return@withLock false
        action(device)
    }

    /**
     * 每 [POLL_INTERVAL_MS] 拉一次远端状态。
     *
     * 只用来刷新进度与"被电视端暂停/停止"的显示；连续两次读失败判定为断开，
     * 避免电视拔电后 UI 一直显示"正在投屏"。
     */
    private fun startPolling(device: CastDevice) {
        pollJob = scope.launch {
            var failures = 0
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val next = engine.readSession(device)
                if (next.state == CastPlaybackState.Error) {
                    if (++failures >= MAX_POLL_FAILURES) {
                        session = null
                        _status.value = CastUiStatus.Failed(REASON_LOST)
                        return@launch
                    }
                } else {
                    failures = 0
                    session = next
                    _status.value = CastUiStatus.Connected(next)
                }
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 5_000L
        const val MAX_POLL_FAILURES = 2
        const val REASON_REJECTED = "电视没有接受这次投屏"
        const val REASON_LOST = "与电视的连接已断开"
        const val SEARCH_GUARD_MS = 2_000L
    }
}

/**
 * 进程级投屏会话的构造入口。
 *
 * 实现保持 `internal`：`:app` 只拿接口，协议细节（SSDP/SOAP/裸 Socket）不出这个包，
 * 以后换实现或升级协议不影响装配层。
 */
fun createCastRepository(context: Context, scope: CoroutineScope): CastRepository {
    CastLog.install(context)
    return DlnaCastRepository(scope = scope, engine = DlnaCastEngine(SsdpDiscovery(context)))
}
