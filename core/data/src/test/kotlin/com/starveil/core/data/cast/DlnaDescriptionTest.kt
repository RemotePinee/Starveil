package com.starveil.core.data.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 设备描述解析：控制地址可能是相对路径，必须按 LOCATION 解析成绝对地址。 */
class DlnaDescriptionTest {

    private val location = "http://192.168.1.20:49152/desc/device.xml"

    private val xml = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <friendlyName>客厅电视</friendlyName>
            <UDN>uuid:renderer-1</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>/upnp/control/avt</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <controlURL>/upnp/control/rcs</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun `parses name udn and both control urls`() {
        val device = DlnaDescription.parse(location, xml)

        assertNotNull(device)
        assertEquals("uuid:renderer-1", device?.id)
        assertEquals("客厅电视", device?.name)
        assertEquals("192.168.1.20", device?.host)
        assertEquals("http://192.168.1.20:49152/upnp/control/avt", device?.controlUrl)
        assertEquals("http://192.168.1.20:49152/upnp/control/rcs", device?.renderingControlUrl)
    }

    @Test
    fun `namespace prefixed document is parsed too`() {
        val prefixed = """
            <r:root xmlns:r="urn:schemas-upnp-org:device-1-0">
              <r:device>
                <r:friendlyName>书房盒子</r:friendlyName>
                <r:UDN>uuid:renderer-2</r:UDN>
                <r:serviceList>
                  <r:service>
                    <r:serviceType>urn:schemas-upnp-org:service:AVTransport:1</r:serviceType>
                    <r:controlURL>/ctl/avt</r:controlURL>
                  </r:service>
                </r:serviceList>
              </r:device>
            </r:root>
        """.trimIndent()

        val device = DlnaDescription.parse(location, prefixed)

        assertEquals("书房盒子", device?.name)
        assertEquals("http://192.168.1.20:49152/ctl/avt", device?.controlUrl)
        assertNull(device?.renderingControlUrl)
    }

    @Test
    fun `device without avtransport is not castable`() {
        val withoutAv = """
            <root><device><friendlyName>音箱</friendlyName>
            <serviceList><service>
            <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
            <controlURL>/rcs</controlURL></service></serviceList>
            </device></root>
        """.trimIndent()

        assertNull(DlnaDescription.parse(location, withoutAv))
    }

    @Test
    fun `missing friendly name falls back to host`() {
        val noName = xml.replace("<friendlyName>客厅电视</friendlyName>", "")

        assertEquals("192.168.1.20", DlnaDescription.parse(location, noName)?.name)
    }

    @Test
    fun `control url with colon in first segment is joined by prefix`() {
        // 真机样本（投屏电视R2）：controlURL 首段带冒号，URI.resolve 会抛异常，
        // 整台设备会被判成"没有 AVTransport"。
        val realWorld = """
            <root><device><friendlyName>投屏电视R2</friendlyName>
            <UDN>uuid:f3c6e9cc</UDN>
            <serviceList><service>
            <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
            <controlURL>_urn:schemas-upnp-org:service:AVTransport_control</controlURL>
            </service></serviceList></device></root>
        """.trimIndent()

        val device = DlnaDescription.parse("http://192.168.1.239:49152/description.xml", realWorld)

        assertEquals(
            "http://192.168.1.239:49152/_urn:schemas-upnp-org:service:AVTransport_control",
            device?.controlUrl,
        )
    }
}
