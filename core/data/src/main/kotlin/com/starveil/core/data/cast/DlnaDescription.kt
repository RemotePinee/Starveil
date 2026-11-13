package com.starveil.core.data.cast

import com.starveil.core.model.CastDevice
import java.net.URL

/**
 * 设备描述文档（`LOCATION` 指向的那份 XML）的解析。
 *
 * 为什么用正则而不是 XmlPullParser：这份文档只有几百字节、字段固定三四个，
 * 但它在**单元测试**里要跑——Android 的 XmlPullParser 是 android.jar 里的桩，
 * 在 JVM 单测上直接抛 "not mocked"。正则的代价是"只能读、不能改"，这里恰好只读。
 * 文档大小已在 [LanHttp] 限制到 256KB，正则不会吃到超长输入。
 *
 * 两个必须容忍的写法差异（不同厂商都出现过）：
 * - 命名空间前缀：`<service>` 与 `<u:service>`；
 * - 标签带属性：`<serviceList xmlns="...">`。
 */
internal object DlnaDescription {

    /** 文档里声明的全部 serviceType，诊断"为什么没认成渲染器"时用。 */
    fun serviceTypes(xml: String): List<String> =
        SERVICE_TYPE.findAll(xml).map { it.groupValues[1].trim() }.distinct().toList()


    /** 解析出可直接用于控制的设备；缺 AVTransport 控制地址的视为不可投。 */
    fun parse(location: String, xml: String): CastDevice? {
        val base = runCatching { URL(location) }.getOrNull() ?: return null
        return base.let { url ->
            val host = url.host ?: return@let null
            val control = controlUrlFor(xml, SERVICE_AV_TRANSPORT, url) ?: return@let null
            CastDevice(
                id = tagValue(xml, "UDN")?.takeIf { it.isNotBlank() } ?: location,
                name = tagValue(xml, "friendlyName")?.takeIf { it.isNotBlank() } ?: host,
                host = host,
                controlUrl = control,
                renderingControlUrl = controlUrlFor(xml, SERVICE_RENDERING_CONTROL, url),
            )
        }
    }

    /**
     * 在 `<service>` 块里找某个 serviceType 的 controlURL。
     *
     * 设备可能同时声明 AVTransport 与 RenderingControl，也可能只声明前者；
     * 控制地址可能是相对路径，必须按描述文档的地址解析成绝对地址。
     */
    private fun controlUrlFor(xml: String, serviceType: String, base: URL): String? =
        SERVICE_BLOCK.findAll(xml).firstNotNullOfOrNull { block ->
            val type = tagValue(block.value, "serviceType").orEmpty()
            if (!type.contains(serviceType)) return@firstNotNullOfOrNull null
            resolve(base, tagValue(block.value, "controlURL").orEmpty().trim())
        }

    /**
     * 把 controlURL 拼成绝对地址。
     *
     * **不能用 `URI.resolve`**：不少国产电视的 controlURL 长这样
     * `_urn:schemas-upnp-org:service:AVTransport_control`——首段带冒号，按 RFC 3986
     * 不是合法相对引用（`_` 也不是合法 scheme 首字符），`URI.create` 直接抛异常，
     * 结果整台设备被当成"没有 AVTransport"丢掉。这里按目录前缀手拼，绕开这个坑。
     */
    private fun resolve(base: URL, control: String): String? {
        val path = control.trim()
        val absolute = path.startsWith("http://", ignoreCase = true) ||
            path.startsWith("https://", ignoreCase = true)
        val root = "${base.protocol}://${base.authority}"
        return when {
            path.isEmpty() -> null
            absolute -> path
            path.startsWith("/") -> root + path
            else -> base.toString().substringBeforeLast('/', "") + "/" + path
        }
    }

    /** 取标签文本；`<u:tag>`、`<tag attr="x">` 都认。 */
    private fun tagValue(xml: String, tag: String): String? {
        val name = Regex.escape(tag)
        val pattern = Regex("<(?:\\w+:)?$name[^>]*>(.*?)</(?:\\w+:)?$name>", RegexOption.DOT_MATCHES_ALL)
        return pattern.find(xml)?.groupValues?.getOrNull(1)?.trim()
    }

    private const val SERVICE_AV_TRANSPORT = "AVTransport"
    private const val SERVICE_RENDERING_CONTROL = "RenderingControl"
    private val SERVICE_TYPE =
        Regex("<(?:\\w+:)?serviceType[^>]*>(.*?)</(?:\\w+:)?serviceType>", RegexOption.DOT_MATCHES_ALL)
    private val SERVICE_BLOCK =
        Regex("<(?:\\w+:)?service[^>]*>(.*?)</(?:\\w+:)?service>", RegexOption.DOT_MATCHES_ALL)
}
