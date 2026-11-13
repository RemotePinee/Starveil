package com.starveil.core.data.cast

import com.starveil.core.model.CastPlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 协议报文与解析：真实电视千差万别，但"我们自己发出去的东西"必须是对的。 */
class DlnaSoapTest {

    @Test
    fun `didl metadata carries title class and res`() {
        val didl = DlnaSoap.didlLite("http://cdn.example.com/a.mp4", "测试片", "video/mp4")

        assertTrue(didl.startsWith("<DIDL-Lite"))
        assertTrue(didl.contains("<dc:title>测试片</dc:title>"))
        assertTrue(didl.contains("object.item.videoItem"))
        assertTrue(didl.contains("protocolInfo=\"http-get:*:video/mp4:*\""))
    }

    @Test
    fun `time formatting round trips`() {
        assertEquals("00:00:00", formatTime(0))
        assertEquals("01:02:03", formatTime(((1 * 3600) + (2 * 60) + 3) * 1000L))
        assertEquals(3_723_000L, parseTime("01:02:03"))
        assertEquals(0L, parseTime("bad"))
    }

    @Test
    fun `transport state and position are parsed from soap response`() {
        val xml = """
            <s:Envelope><s:Body><u:GetPositionInfoResponse>
            <CurrentTransportState>PAUSED_PLAYBACK</CurrentTransportState>
            <TrackDuration>00:45:10</TrackDuration>
            <RelTime>00:12:34</RelTime>
            </u:GetPositionInfoResponse></s:Body></s:Envelope>
        """.trimIndent()

        assertEquals("PAUSED_PLAYBACK", DlnaSoap.parseTransportState(xml))
        assertEquals(754_000L, DlnaSoap.parseRelTimeMs(xml))
        assertEquals(2_710_000L, DlnaSoap.parseDurationMs(xml))
        assertEquals(CastPlaybackState.Paused, "PAUSED_PLAYBACK".toCastState())
    }

    @Test
    fun `unknown transport state falls back to idle`() {
        assertEquals(CastPlaybackState.Idle, "NO_MEDIA_PRESENT".toCastState())
        assertEquals(CastPlaybackState.Idle, "something-new".toCastState())
    }

    @Test
    fun `mime follows extension and defaults to mp4`() {
        assertEquals("application/vnd.apple.mpegurl", mimeOf("https://x/a.m3u8?token=1"))
        assertEquals("video/x-matroska", mimeOf("https://x/a.mkv"))
        assertEquals("video/mp4", mimeOf("https://x/a.unknown"))
    }

    @Test
    fun `missing tag yields null instead of throwing`() {
        assertNull(tagValue("<a></a>", "CurrentTransportState"))
    }
}
