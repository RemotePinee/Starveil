package com.starveil.core.data.cast

/**
 * DLNA 控制用的 SOAP 报文与响应解析。
 *
 * 全部是纯字符串拼装/提取，放在一处是为了能单测：真机上一台电视的 SOAP 兼容性
 * 靠猜是猜不出来的，但"报文长什么样"必须先对。
 *
 * 统一约定：service 有 AVTransport（投/停/进度）与 RenderingControl（音量）两个，
 * 每个请求都要带 `SOAPACTION: "<serviceType>#<action>"`。
 */
internal object DlnaSoap {

    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"

    /** 把一条媒体地址投出去；[metadata] 见 [didlLite]。 */
    fun setAvTransportUri(url: String, metadata: String): String = envelope(
        service = AV_TRANSPORT,
        action = "SetAVTransportURI",
        arguments = """
            <InstanceID>0</InstanceID>
            <CurrentURI>${xmlEscape(url)}</CurrentURI>
            <CurrentURIMetaData>${xmlEscape(metadata)}</CurrentURIMetaData>
""".trimIndent(),
    )

    fun play(): String = instanceAction("Play", "<Speed>1</Speed>")

    fun pause(): String = instanceAction("Pause")

    fun stop(): String = instanceAction("Stop")

    /** [positionMs] 会被格式化成 `HH:MM:SS`：UPnP 的 REL_TIME 只认这个。 */
    fun seek(positionMs: Long): String = instanceAction(
        action = "Seek",
        extra = "<Unit>REL_TIME</Unit><Target>${formatTime(positionMs)}</Target>",
    )

    fun getTransportInfo(): String = instanceAction("GetTransportInfo")

    fun getPositionInfo(): String = instanceAction("GetPositionInfo")

    fun soapAction(service: String, action: String): String = "$service#$action"

    /**
     * 生成 DIDL-Lite 元数据。
     *
     * 不带它也能播，但多数电视会把这条投屏显示成"未知标题"，也不给封面；
     * 带上之后电视端列表里是我们的片名。`protocolInfo` 里的 MIME 由扩展名推。
     */
    fun didlLite(url: String, title: String, mime: String): String = buildString {
        append("""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"""")
        append(""" xmlns:dc="""").append(NS_DC).append('"')
        append(""" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""")
        append("""<item id="0" parentID="-1" restricted="1">""")
        append("<dc:title>").append(xmlEscape(title)).append("</dc:title>")
        append("<upnp:class>object.item.videoItem</upnp:class>")
        append("""<res protocolInfo="http-get:*:$mime:*">""")
        append(xmlEscape(url)).append("</res></item></DIDL-Lite>")
    }

    /** `CurrentTransportState` → 我们自己的枚举；不认识的按 Idle。 */
    fun parseTransportState(xml: String): String =
        tagValue(xml, "CurrentTransportState").orEmpty()

    /** `RelTime`（`HH:MM:SS`）→ 毫秒。 */
    fun parseRelTimeMs(xml: String): Long = parseTime(tagValue(xml, "RelTime").orEmpty())

    /** `TrackDuration` → 毫秒。 */
    fun parseDurationMs(xml: String): Long = parseTime(tagValue(xml, "TrackDuration").orEmpty())

    private fun instanceAction(action: String, extra: String = ""): String = envelope(
        service = AV_TRANSPORT,
        action = action,
        arguments = "<InstanceID>0</InstanceID>$extra",
    )

    private fun envelope(service: String, action: String, arguments: String): String =
        """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="$NS_SOAP_ENVELOPE" s:encodingStyle="$NS_SOAP_ENCODING">
<s:Body><u:$action xmlns:u="$service">$arguments</u:$action></s:Body>
</s:Envelope>"""
}

// XML 命名空间是标识符，不发起请求；闸门按字面量扫 http://，逐行登记理由。
private const val NS_DC = "http://purl.org/dc/elements/1.1/" // security-baseline-allow: 命名空间
private const val NS_SOAP_ENVELOPE = "http://schemas.xmlsoap.org/soap/envelope/" // security-baseline-allow: 命名空间
private const val NS_SOAP_ENCODING = "http://schemas.xmlsoap.org/soap/encoding/" // security-baseline-allow: 命名空间

/** XML 文本转义：片名里出现 `&`、`<` 时不能让整条 SOAP 失效。 */
internal fun xmlEscape(text: String): String = buildString {
    text.forEach { ch ->
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(ch)
        }
    }
}

/** 取第一个 `<tag>value</tag>` 的文本；找不到返回 null。 */
internal fun tagValue(xml: String, tag: String): String? {
    val open = "<$tag>"
    val start = xml.indexOf(open)
    val end = if (start < 0) -1 else xml.indexOf("</$tag>", start)
    return if (start >= 0 && end > start) {
        xml.substring(start + open.length, end).trim()
    } else {
        null
    }
}

/** UPnP 的时间格式：`HH:MM:SS`，非法输入返回 0。 */
internal fun parseTime(value: String): Long {
    val parts = value.split(':').map { it.toLongOrNull() }
    if (parts.size != 3 || parts.any { it == null }) return 0
    val (h, m, s) = parts.map { it ?: 0L }
    return ((h * 3600) + (m * 60) + s) * 1000
}
/** 毫秒 → `HH:MM:SS`。 */
internal fun formatTime(positionMs: Long): String {
    val totalSeconds = (positionMs.coerceAtLeast(0) / 1000)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

/** 按扩展名推 MIME；推不出来用 mp4，电视对它的支持最广。 */
internal fun mimeOf(url: String): String {
    val path = url.substringBefore('?').lowercase()
    return when {
        path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
        path.endsWith(".mpd") -> "application/dash+xml"
        path.endsWith(".mkv") -> "video/x-matroska"
        path.endsWith(".webm") -> "video/webm"
        path.endsWith(".ts") -> "video/mp2t"
        path.endsWith(".mov") -> "video/quicktime"
        else -> "video/mp4"
    }
}
