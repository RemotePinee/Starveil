package com.starveil.core.data.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket

/**
 * SSDP 报文层：发出去的 M-SEARCH 与收回来的响应。
 *
 * 真机联调之前，这两件事必须先在单测里对：M-SEARCH 少一个头（UA / MX / ST）
 * 就可能有设备不理；`Location` 解析大小写不统一会让设备列表整片空掉。
 */
class SsdpDatagramTest {

    @Test
    fun `search datagram carries every required header`() {
        val text = searchDatagram("ssdp:all")

        assertTrue(text.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(text.contains("HOST: 239.255.255.250:1900\r\n"))
        assertTrue(text.contains("MAN: \"ssdp:discover\"\r\n"))
        assertTrue(text.contains("MX: 3\r\n"))
        assertTrue(text.contains("ST: ssdp:all\r\n"))
        assertTrue(text.contains("USER-AGENT:"))
        assertTrue(text.endsWith("\r\n\r\n"))
    }

    @Test
    fun `location is parsed from search response`() {
        val packet = packetOf(
            "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\n" +
                "LOCATION: http://192.168.1.20:49152/desc.xml\r\nST: upnp:rootdevice\r\n\r\n",
        )

        assertEquals("http://192.168.1.20:49152/desc.xml", parseLocation(packet))
    }

    @Test
    fun `location is parsed from notify announcement`() {
        val packet = packetOf(
            "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                "location: http://192.168.1.30:8080/device.xml\r\nNTS: ssdp:alive\r\n\r\n",
        )

        assertEquals("http://192.168.1.30:8080/device.xml", parseLocation(packet))
    }

    @Test
    fun `packet without location is ignored`() {
        assertNull(parseLocation(packetOf("HTTP/1.1 200 OK\r\nST: ssdp:all\r\n\r\n")))
        assertNull(parseLocation(packetOf("NOTIFY * HTTP/1.1\r\nNTS: ssdp:byebye\r\n\r\n")))
    }

    @Test
    fun `soap fault is translated into a readable reason`() {
        val fault = """
            <s:Envelope><s:Body><s:Fault><detail><UPnPError>
            <errorCode>716</errorCode><errorDescription>Resource not found</errorDescription>
            </UPnPError></detail></s:Fault></s:Body></s:Envelope>
        """.trimIndent()

        assertEquals("电视拒绝播放这个地址（片源无法访问）", faultReasonOf(fault))
    }

    @Test
    fun `unknown fault code keeps the code so users can report it`() {
        val fault = "<UPnPError><errorCode>999</errorCode><errorDescription>Boom</errorDescription></UPnPError>"

        val reason = faultReasonOf(fault)

        assertTrue(reason.contains("999"))
        assertTrue(reason.contains("Boom"))
    }

    private fun packetOf(text: String): DatagramPacket {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return DatagramPacket(bytes, bytes.size)
    }
}
