package com.starveil.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 缓存键规范化（[videoCacheKey]）。
 *
 * 两条边界是这次 CDN 签名改动引出来的，都必须钉住：
 * - 签名参数每次下发都变 → 必须剥掉，否则同一集永远命中不了缓存；
 * - 其它 query 是源站的资源标识 → 必须留下，否则不同分片会被并成同一份缓存。
 */
class VideoCacheKeyTest {

    @Test
    fun `signature params are stripped so the same episode maps to one key`() {
        val first = "https://cdn.example.com/vod/1/2/index.m3u8" +
            "?t=1750000000&exper=1&rlimit=3&us=abc&sign=deadbeef"
        val second = "https://cdn.example.com/vod/1/2/index.m3u8" +
            "?t=1750021600&exper=1&rlimit=3&us=xyz&sign=cafebabe"

        assertEquals("https://cdn.example.com/vod/1/2/index.m3u8", videoCacheKey(first))
        assertEquals(videoCacheKey(first), videoCacheKey(second))
    }

    @Test
    fun `origin query params survive because they identify the file`() {
        val url = "https://cdn.example.com/hls/seg-3.ts?id=88&t=1750000000&sign=abc"

        assertEquals("https://cdn.example.com/hls/seg-3.ts?id=88", videoCacheKey(url))
    }

    @Test
    fun `url without query is returned unchanged`() {
        val url = "https://cdn.example.com/vod/1.mp4"

        assertEquals(url, videoCacheKey(url))
    }

    @Test
    fun `empty query leaves no dangling question mark`() {
        assertEquals("https://cdn.example.com/a.m3u8", videoCacheKey("https://cdn.example.com/a.m3u8?"))
        assertEquals("https://cdn.example.com/a.m3u8", videoCacheKey("https://cdn.example.com/a.m3u8?us=&t="))
    }

    @Test
    fun `fragment is kept and does not leak into the query scan`() {
        assertEquals(
            "https://cdn.example.com/a.m3u8#chapter=2",
            videoCacheKey("https://cdn.example.com/a.m3u8?us=abc#chapter=2"),
        )
    }

    @Test
    fun `param names are matched case sensitively so lookalikes are not dropped`() {
        // `T` / `Sign` 不是 CDN 的签名参数：误剥就是把两份内容并成一份缓存，那比不命中严重
        val url = "https://cdn.example.com/a.m3u8?T=1&Sign=2"

        assertEquals(url, videoCacheKey(url))
    }

    @Test
    fun `non url input is handled without throwing`() {
        assertEquals("::not-a-url::", videoCacheKey("::not-a-url::"))
        assertEquals("plain-path.m3u8", videoCacheKey("plain-path.m3u8?sign=1"))
    }
}
