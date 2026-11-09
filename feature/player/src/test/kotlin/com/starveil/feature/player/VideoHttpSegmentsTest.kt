package com.starveil.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清单解析的边界：真机闪退（首页进播放，2026-11-10）就是这里漏了一条——
 * 上游回 404 JSON，被当成清单解析，`URI.resolve` 抛异常把进程带走。
 */
class VideoHttpSegmentsTest {

    private val playlist = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:4
        #EXTINF:4.0,
        seg-1.ts
        #EXTINF:4.0,
        https://cdn.example.com/seg-2.ts
    """.trimIndent()

    @Test
    fun `json error body is not a playlist and yields nothing`() {
        val body = """{"ok":false,"error":{"code":"NOT_FOUND","msg":"路径不存在"}}"""

        assertTrue(segmentsFrom("https://host/a.m3u8", body, 2).isEmpty())
    }

    @Test
    fun `blank body yields nothing`() {
        assertTrue(segmentsFrom("https://host/a.m3u8", "   \n\n", 2).isEmpty())
    }

    @Test
    fun `relative and absolute segments both resolve`() {
        val urls = segmentsFrom("https://host/path/a.m3u8", playlist, 4)

        assertEquals(
            listOf("https://host/path/seg-1.ts", "https://cdn.example.com/seg-2.ts"),
            urls,
        )
    }

    @Test
    fun `limit caps the prefetch window`() {
        assertEquals(1, segmentsFrom("https://host/path/a.m3u8", playlist, 1).size)
    }

    @Test
    fun `a malformed line is skipped instead of throwing`() {
        val body = "#EXTM3U\nseg-1.ts\n{垃圾}\nseg-2.ts"

        val urls = segmentsFrom("https://host/path/a.m3u8", body, 3)

        assertTrue(urls.contains("https://host/path/seg-1.ts"))
        assertTrue(urls.none { it.contains("垃圾") })
    }
}
