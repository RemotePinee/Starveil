package com.starveil.core.data.cast

import com.starveil.core.model.CastDevice
import com.starveil.core.model.CastPlaybackState
import com.starveil.core.model.CastSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 一条投屏指令的结果。失败带原因——面板会原样显示给用户，不能只有 true/false。 */
internal sealed interface CastAttempt {
    data object Ok : CastAttempt
    data class Failed(val reason: String) : CastAttempt
}

/**
 * 投屏协议能力的抽象。
 *
 * 抽出接口是为了让会话层（[DlnaCastRepository]）能被单测覆盖：真机不可用时，
 * 用假 engine 就能验证"搜索 → 连接 → 轮询 → 掉线 → 断开回进度"整条状态机。
 */
internal interface CastEngine {

    suspend fun discover(timeoutMs: Long): List<CastDevice>

    /**
     * 下面几条都是**阻塞 Socket** 调用，必须是 suspend 并在 IO 线程执行。
     * 之前 `castTo` 直接从 UI 协程（Main）调用，被系统的 NetworkOnMainThread 拦掉，
     * 异常又被吞成"电视没有响应"——这是"搜得到但投不上"的真正原因。
     */
    suspend fun cast(device: CastDevice, url: String, title: String): CastAttempt

    suspend fun play(device: CastDevice): Boolean

    suspend fun pause(device: CastDevice): Boolean

    suspend fun stop(device: CastDevice): Boolean

    /** 读一次远端状态；读不到时返回 state 为 [CastPlaybackState.Error] 的会话。 */
    suspend fun readSession(device: CastDevice): CastSession
}

/**
 * DLNA 控制点：发现 → 投 → 控制 → 读进度。
 *
 * 这一层只做"发一条指令、读一条状态"，不持有会话、不管理生命周期——
 * 那些在 [DlnaCastRepository]。
 */
internal class DlnaCastEngine(
    private val discovery: SsdpDiscovery,
    private val http: LanHttp = LanHttp(),
) : CastEngine {

    /**
     * 搜索并抓描述文档。
     *
     * `ssdp:all` 会捞回一堆非投屏设备（路由器、NAS、打印机），所以：
     * - 抓取**并发**进行，单台超时 1.5s，不让一台慢设备拖住整轮搜索；
     * - 数量封顶，避免大内网里几百台设备把搜索拖成十几秒；
     * - 描述里没有 AVTransport 的（`DlnaDescription.parse` 返回 null）直接丢掉。
     */
    override suspend fun discover(timeoutMs: Long): List<CastDevice> = coroutineScope {
        val locations = discovery.searchLocations(timeoutMs).take(MAX_DESCRIPTIONS)
        val devices = locations
            .map { location -> async { withTimeoutOrNull(DESCRIPTION_TIMEOUT_MS) { fetch(location) } } }
            .awaitAll()
            .filterNotNull()
            .distinctBy { it.id }
        CastLog.debug { "discover: locations=${locations.size} castable=${devices.size}" }
        devices
    }

    /**
     * 投一条地址：先 SetAVTransportURI，成功后再 Play（顺序不能反）。
     *
     * **元数据降级**：不少电视与投屏 App（乐播、极光云视听等）会拒绝带 DIDL-Lite 的
     * `CurrentURIMetaData`（回 500 + UPnPError 714/716）。这时退回空元数据重发一次，
     * 播放器仍能按 URI 起播，只是电视端的列表里看不到片名——先能投，再谈好看。
     */
    override suspend fun cast(device: CastDevice, url: String, title: String): CastAttempt =
        withContext(Dispatchers.IO) {
            val metadata = DlnaSoap.didlLite(url, title, mimeOf(url))
            val withMetadata = send(device, DlnaSoap.setAvTransportUri(url, metadata), SET_URI)
            val accepted = if (withMetadata.ok) {
                withMetadata
            } else {
                send(device, DlnaSoap.setAvTransportUri(url, ""), SET_URI)
            }
            if (!accepted.ok) {
                CastAttempt.Failed(accepted.reason ?: REASON_UNREACHABLE)
            } else {
                val played = send(device, DlnaSoap.play(), "Play")
                if (played.ok) CastAttempt.Ok else CastAttempt.Failed(played.reason ?: REASON_UNREACHABLE)
            }
        }

    override suspend fun play(device: CastDevice): Boolean = callOnIo { send(device, DlnaSoap.play(), "Play").ok }

    override suspend fun pause(device: CastDevice): Boolean = callOnIo { send(device, DlnaSoap.pause(), "Pause").ok }

    override suspend fun stop(device: CastDevice): Boolean = callOnIo { send(device, DlnaSoap.stop(), "Stop").ok }

    override suspend fun readSession(device: CastDevice): CastSession = withContext(Dispatchers.IO) {
        val transport = soap(device, DlnaSoap.getTransportInfo(), "GetTransportInfo")
        val position = soap(device, DlnaSoap.getPositionInfo(), "GetPositionInfo")
        if (transport == null || position == null) {
            CastSession(device = device, state = CastPlaybackState.Error)
        } else {
            CastSession(
                device = device,
                state = DlnaSoap.parseTransportState(transport).toCastState(),
                positionMs = DlnaSoap.parseRelTimeMs(position),
                durationMs = DlnaSoap.parseDurationMs(position),
            )
        }
    }

    /** 阻塞 Socket 一律在 IO 上跑；这是投屏链路唯一允许的执行线程。 */
    private suspend fun <T> callOnIo(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun soap(device: CastDevice, body: String, action: String): String? =
        http.post(device.controlUrl, body, DlnaSoap.soapAction(DlnaSoap.AV_TRANSPORT, action))
            ?.takeIf { it.code in 200..299 }
            ?.body

    /** 发一条指令，把失败原因从 SOAP fault 里翻成人话。 */
    private fun send(device: CastDevice, body: String, action: String): SoapResult {
        val url = device.controlUrl
        val response = http.post(url, body, DlnaSoap.soapAction(DlnaSoap.AV_TRANSPORT, action))
        if (response == null) {
            CastLog.debug { "soap $action -> no response from ${CastLog.hostOf(url)}" }
            return SoapResult(ok = false, reason = REASON_UNREACHABLE)
        }
        CastLog.debug { "soap $action -> ${response.code}" }
        return if (response.code in 200..299) {
            SoapResult(ok = true, reason = null)
        } else {
            SoapResult(ok = false, reason = faultReasonOf(response.body))
        }
    }

    private suspend fun fetch(location: String): CastDevice? = withContext(Dispatchers.IO) {
        val response = http.getRaw(location)
        val xml = response?.takeIf { it.code in 200..299 }?.body
        val device = xml?.let { DlnaDescription.parse(location, it) }
        if (device == null) {
            // 诊断用：状态码 + 完整文档（压成一行，最多 1500 字）。局域网设备描述里
            // 没有用户隐私；地址仍只留 host，路径可能带随机串。
            CastLog.debug {
                "skip ${CastLog.hostOf(location)} code=${response?.code} " +
                    "services=${response?.body?.let(DlnaDescription::serviceTypes)} " +
                    "body=${response?.body?.take(1_500)?.replace('\n', ' ')}"
            }
        }
        device
    }

    private companion object {
        const val SET_URI = "SetAVTransportURI"
        const val REASON_UNREACHABLE = "电视没有响应，检查它是否还在同一网络"
    }
}

private data class SoapResult(val ok: Boolean, val reason: String?)

/**
 * 把 UPnPError 翻成中文。
 *
 * 错误码语义见 UPnP AVTransport 规范；这里只覆盖投屏实际会遇到的那几个，
 * 其余原文带出，便于用户截图反馈。
 */
internal fun faultReasonOf(body: String): String {
    val code = tagValue(body, "errorCode")?.toIntOrNull()
    val description = tagValue(body, "errorDescription")?.takeIf { it.isNotBlank() }
    val mapped = when (code) {
        701 -> "电视当前状态不允许这个操作（可能正在放别的内容）"
        714 -> "电视不支持这个片源格式"
        716 -> "电视拒绝播放这个地址（片源无法访问）"
        718 -> "电视上的播放实例无效，重新搜索设备再试"
        else -> null
    }
    return mapped ?: buildString {
        append("电视拒绝了这次投屏")
        code?.let { append("（错误码 ").append(it).append('）') }
        description?.let { append("：").append(it) }
    }
}

/** 单台设备的描述文档抓取上限：再慢也不能拖住整轮搜索。 */
private const val DESCRIPTION_TIMEOUT_MS = 1_500L

/** `ssdp:all` 在大内网里能捞回几十上百台设备，抓取数量封顶。 */
private const val MAX_DESCRIPTIONS = 24

/** UPnP 的 `CurrentTransportState` → 本地枚举；不认识的（部分电视回 `NO_MEDIA_PRESENT`）当 Idle。 */
internal fun String.toCastState(): CastPlaybackState = when (trim().uppercase()) {
    "PLAYING" -> CastPlaybackState.Playing
    "PAUSED_PLAYBACK", "PAUSED" -> CastPlaybackState.Paused
    "TRANSITIONING", "STOPPED" -> CastPlaybackState.Buffering
    "NO_MEDIA_PRESENT", "IDLE" -> CastPlaybackState.Idle
    else -> CastPlaybackState.Idle
}
