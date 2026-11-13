package com.starveil.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 缓存键规范化（[videoCacheKey]）与资源类型判定（[classifyPlaybackResource]）。
 *
 * 这两条规则决定"什么进 SimpleCache、进缓存时叫什么名字"，错了的代价不对等：
 * - 清单误进缓存 → 客户端拿着过期签名请求分片，源站 403；
 * - 分片键不稳定 → 缓存永远命不中，300MB 的 LRU 白写白淘汰。
 *
 * 只测字符串重载：本模块单测跑纯 JVM（不引 Robolectric），造不出 `android.net.Uri`；
 * `videoCacheKey(Uri)` 只是 `toString()` 转给它，没有额外逻辑。
 */
class VideoCacheKeyTest {

    @Test
    fun `segment key is the path, every query param dropped`() {
        val first = "https://cdn.example.com/vod/1/2/c5c47bf5.ts?sign=deadbeef&timestamp=1794498739"
        val second = "https://cdn.example.com/vod/1/2/c5c47bf5.ts?sign=cafebabe&timestamp=1794499999"
        val other = "https://cdn.example.com/vod/1/2/77ad10e3.ts?sign=deadbeef"

        assertEquals("https://cdn.example.com/vod/1/2/c5c47bf5.ts", videoCacheKey(first))
        assertEquals(videoCacheKey(first), videoCacheKey(second))
        assertNotEquals(videoCacheKey(first), videoCacheKey(other))
    }

    @Test
    fun `non segment key keeps no query until a param is whitelisted`() {
        val url = "https://cdn.example.com/hls/index.m3u8?t=1750000000&us=abc&sign=deadbeef"

        assertEquals("https://cdn.example.com/hls/index.m3u8", videoCacheKey(url))
    }

    @Test
    fun `url without query is returned unchanged and fragment never joins the key`() {
        assertEquals("https://cdn.example.com/vod/1.mp4", videoCacheKey("https://cdn.example.com/vod/1.mp4"))
        assertEquals(
            "https://cdn.example.com/a.m3u8",
            videoCacheKey("https://cdn.example.com/a.m3u8?us=abc#chapter=2"),
        )
    }

    @Test
    fun `resource kind decides who reaches the cache`() {
        assertEquals(ResourceKind.MANIFEST, classifyPlaybackResource("https://c.example.com/a/index.m3u8?t=1"))
        assertEquals(ResourceKind.MANIFEST, classifyPlaybackResource("https://c.example.com/live/playlist?t=1"))
        assertEquals(ResourceKind.SEGMENT, classifyPlaybackResource("https://c.example.com/a/seg-1.m4s?sign=1"))
        // 认不出来的一律不缓存：少缓存只是没提速，多缓存是死数据
        assertEquals(ResourceKind.UNKNOWN, classifyPlaybackResource("https://c.example.com/a/stream?id=1"))
    }

    @Test
    fun `non url input is handled without throwing`() {
        assertEquals("::not-a-url::", videoCacheKey("::not-a-url::"))
        assertEquals("plain-path.m3u8", videoCacheKey("plain-path.m3u8?sign=1"))
    }
}
