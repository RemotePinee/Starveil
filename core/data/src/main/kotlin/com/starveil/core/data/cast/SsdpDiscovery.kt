package com.starveil.core.data.cast

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.BindException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicInteger

/**
 * SSDP 搜索：在局域网里找可投屏的接收端（电视 / 盒子 / 乐播、极光这类投屏 App）。
 *
 * 设计约束（每条都对应真实故障）：
 *
 * 1. **组播组 + 1900 端口**：监听绑到 1900 并 `joinGroup`，否则只收得到"对我们
 *    M-SEARCH 的单播回包"，收不到设备的 `NOTIFY ssdp:alive`；1900 被占用时退临时端口。
 * 2. **出口钉在 Wi-Fi 网卡**：手机上开着代理 / VPN 时，不指定接口的组播会被 tun 吞掉，
 *    表现就是"Wi-Fi 明明正常，一台设备都搜不到"。
 * 3. **先监听再搜索**：反过来的话最早的回包会在监听就位前丢失。
 * 4. **可取消的接收循环**：`socket.receive` 是阻塞调用，循环不检查 `isActive` 时
 *    搜索结束的取消会永远等下去——界面卡在"正在搜索"、MulticastLock 不释放。
 * 5. **搜索窗口是硬上限**：整个搜索被 `withTimeoutOrNull` 包住。
 * 6. **MulticastLock 必须成对**：国产 ROM 默认过滤组播。
 *
 * 诊断：`CastLog` 打印 `packets`（收到几个 SSDP 包）与 `locations`（几个带 LOCATION），
 * 两者一起看才能区分"网络没通"与"我们解析错了"。
 */
internal class SsdpDiscovery(private val context: Context) {

    /**
     * 搜索 [timeoutMs]，返回去重后的设备描述地址（`LOCATION`）。
     *
     * 不抛异常：网络不可用、端口冲突、全部丢包都返回空列表。
     */
    suspend fun searchLocations(timeoutMs: Long): List<String> = withContext(Dispatchers.IO) {
        val lock = acquireMulticastLock()
        val socket = openSocket()
        try {
            if (socket == null) emptyList() else search(socket, timeoutMs)
        } finally {
            socket?.let(::closeQuietly)
            releaseQuietly(lock)
        }
    }

    /** 搜索本体：窗口内收包，窗口到点即返回。 */
    private suspend fun search(socket: MulticastSocket, timeoutMs: Long): List<String> =
        withTimeoutOrNull(timeoutMs + SEARCH_GUARD_MS) {
            coroutineScope {
                val locations = mutableSetOf<String>()
                val packets = AtomicInteger()
                val receiver = launch { receive(socket, locations, packets) }
                try {
                    // 给接收协程一点时间真正进入 receive，再发包
                    delay(RECEIVE_WARMUP_MS)
                    sendSearches(socket)
                    delay(timeoutMs)
                } finally {
                    receiver.cancel()
                }
                val found = snapshot(locations)
                CastLog.debug { "search done: packets=${packets.get()} locations=${found.size}" }
                found
            }
        } ?: run {
            CastLog.debug { "search timed out" }
            emptyList()
        }

    private suspend fun receive(socket: MulticastSocket, sink: MutableSet<String>, packets: AtomicInteger) {
        socket.soTimeout = RECEIVE_POLL_MS
        while (currentCoroutineContext().isActive) {
            val location = receiveOne(socket)
            // 收到任何包都记一次：有包但没 LOCATION = 解析问题；一个都没有 = 网络问题
            packets.incrementAndGet()
            if (location != null) {
                synchronized(sink) { sink.add(location) }
            }
        }
    }

    /** 收一个包；超时是常态，返回 null。 */
    private fun receiveOne(socket: MulticastSocket): String? {
        val packet = DatagramPacket(ByteArray(RECEIVE_BUFFER), RECEIVE_BUFFER)
        val delivered = runCatching { socket.receive(packet) }.isSuccess
        return if (delivered) parseLocation(packet) else null
    }

    private fun snapshot(sink: MutableSet<String>): List<String> = synchronized(sink) { sink.toList() }

    private suspend fun sendSearches(socket: MulticastSocket) {
        for (target in SEARCH_TARGETS) {
            sendOne(socket, target)
            delay(SEARCH_GAP_MS)
        }
    }

    private fun sendOne(socket: MulticastSocket, target: String) {
        val payload = searchDatagram(target).toByteArray(Charsets.UTF_8)
        val packet = DatagramPacket(payload, payload.size, GROUP_ADDRESS, SSDP_PORT)
        val result = runCatching { socket.send(packet) }
        if (result.isFailure) {
            CastLog.debug { "send failed: ${result.exceptionOrNull()?.javaClass?.simpleName}" }
        }
    }

    /**
     * 打开组播 socket：先 `reuseAddress` 再 `bind`，然后钉接口、进组。
     *
     * 直接 `MulticastSocket(1900)` 在端口被占用时抛 `BindException`，整轮搜索直接空掉；
     * 这里退到临时端口，把"能搜到"排在"能收 NOTIFY"之前。
     */
    private fun openSocket(): MulticastSocket? {
        val socket = runCatching { MulticastSocket(null) }.getOrNull() ?: return null
        val wifi = wifiInterface()
        return runCatching {
            socket.reuseAddress = true
            bindPort(socket)
            socket.timeToLive = MULTICAST_TTL
            wifi?.let { socket.networkInterface = it }
            socket.joinGroup(GROUP_SOCKET_ADDRESS, wifi)
            socket
        }.onFailure { failure ->
            CastLog.debug { "socket setup failed: ${failure.javaClass.simpleName}: ${failure.message}" }
            runCatching { socket.close() }
        }.getOrNull()?.also { CastLog.debug { "socket ready localPort=${it.localPort}" } }
    }

    private fun bindPort(socket: MulticastSocket) {
        try {
            socket.bind(InetSocketAddress(SSDP_PORT))
        } catch (_: BindException) {
            CastLog.debug { "port $SSDP_PORT busy, fallback to ephemeral" }
            socket.bind(InetSocketAddress(0))
        }
    }

    /** 带 IPv4 私网地址的 Wi-Fi/以太网卡；找不到返回 null，交给系统选路。 */
    private fun wifiInterface(): NetworkInterface? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .filter { it.name.startsWith("wlan") || it.name.startsWith("eth") }
            .firstOrNull { iface ->
                iface.inetAddresses.toList().any { it is Inet4Address && it.isSiteLocalAddress }
            }
    }.getOrNull()

    private fun closeQuietly(socket: MulticastSocket) {
        runCatching { socket.leaveGroup(GROUP_SOCKET_ADDRESS, null) }
        runCatching { socket.close() }
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? = runCatching {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        wifi.createMulticastLock(MULTICAST_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

    private fun releaseQuietly(lock: WifiManager.MulticastLock?) {
        runCatching { if (lock != null && lock.isHeld) lock.release() }
    }

    private companion object {
        const val SSDP_PORT = 1900
        const val MULTICAST_TTL = 4
        const val MULTICAST_LOCK_TAG = "starveil-cast"
        const val RECEIVE_POLL_MS = 400
        const val RECEIVE_BUFFER = 4 * 1024
        const val RECEIVE_WARMUP_MS = 80L
        const val SEARCH_GAP_MS = 120L

        /** 窗口之外再给的余量：close/leaveGroup 不应把总时长拖出去。 */
        const val SEARCH_GUARD_MS = 1_000L

        /**
         * 四条 ST 各有用途：
         * - `upnp:rootdevice`：多数接收端必答，成熟实现（Cling / jUPnP / UPnPCast）都发；
         * - `ssdp:all`：乐播、极光这类投屏 App 只认它；
         * - `device:MediaRenderer:1`：标准电视 / 盒子；
         * - `service:AVTransport:1`：少数只按服务类型应答的老设备。
         */
        val SEARCH_TARGETS = listOf(
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1",
            "ssdp:all",
        )

        val GROUP_ADDRESS: InetAddress = InetAddress.getByName("239.255.255.250")
        val GROUP_SOCKET_ADDRESS = InetSocketAddress(GROUP_ADDRESS, SSDP_PORT)
    }
}

/**
 * 拼一条 M-SEARCH。
 *
 * `MX: 3` 与 `USER-AGENT` 都不是可选项：MX 给接收端留出延迟应答的时间，个别设备按 UA
 * 过滤请求。两处都是成熟实现里的固定写法。
 */
internal fun searchDatagram(st: String): String = buildString {
    append("M-SEARCH * HTTP/1.1\r\n")
    append("HOST: 239.255.255.250:1900\r\n")
    append("MAN: \"ssdp:discover\"\r\n")
    append("MX: 3\r\n")
    append("ST: ").append(st).append("\r\n")
    append("USER-AGENT: Starveil/1.0 UPnP/1.1 DLNADOC/1.50\r\n")
    append("\r\n")
}

/**
 * 从一条 SSDP 报文里取 `LOCATION`。
 *
 * `HTTP/1.1 200 OK` 与 `NOTIFY * HTTP/1.1` 都带这个头；头部大小写不统一，按小写比较。
 */
internal fun parseLocation(packet: DatagramPacket): String? {
    val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
    return text.lineSequence()
        .firstOrNull { it.startsWith("location:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
