package com.starveil.core.data.cast.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中转投屏的播放列表改写：电视只会照着 playlist 里的 URI 请求，
 * 任何一个没被改写的地址都会绕过手机、直连 CDN（多半直接 403/404）。
 */
class M3u8RewriterTest {

    private val base = "https://cdn.example.com/vod/playlist.m3u8"
    private val proxy = "http://192.168.1.191:41234"

    @Test
    fun `segments are rewritten to the relay with original url as parameter`() {
        val source = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            seg1.ts
            https://cdn.example.com/vod/seg2.ts
        """.trimIndent()

        val rewritten = M3u8Rewriter.rewrite(source, base, proxy)

        val seg1 = "$proxy/cast/media?u=" + M3u8Rewriter.encode("https://cdn.example.com/vod/seg1.ts")
        val seg2 = "$proxy/cast/media?u=" + M3u8Rewriter.encode("https://cdn.example.com/vod/seg2.ts")
        assertTrue(rewritten.contains(seg1))
        assertTrue(rewritten.contains(seg2))
        assertTrue(rewritten.contains("#EXT-X-TARGETDURATION:10"))
    }

    @Test
    fun `encryption key uri is rewritten too`() {
        val source = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x1\nseg1.ts"

        val rewritten = M3u8Rewriter.rewrite(source, base, proxy)

        val key = "$proxy/cast/media?u=" + M3u8Rewriter.encode("https://cdn.example.com/vod/key.bin")
        assertTrue(rewritten.contains("URI=\"$key\""))
    }

    @Test
    fun `root relative uri uses playlist authority`() {
        val rewritten = M3u8Rewriter.rewrite("/abs/seg.ts", base, proxy)

        assertEquals("$proxy/cast/media?u=" + M3u8Rewriter.encode("https://cdn.example.com/abs/seg.ts"), rewritten)
    }
}
