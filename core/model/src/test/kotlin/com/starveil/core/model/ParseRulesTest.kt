package com.starveil.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析规则的边界用例。AGENTS.md 第 7 节要求直链判定、试看判定、URL 归一化
 * 必须覆盖空、null、非法格式——这里逐条对齐。
 */
class ParseRulesTest {

    @Test
    fun `json endpoint detected by json marker and double dot`() {
        assertTrue(ParseRules.usesJsonEndpoint("https://api.example.com/parse?json=1&url="))
        assertTrue(ParseRules.usesJsonEndpoint("https://api.example.com/..json?url="))
        assertFalse(ParseRules.usesJsonEndpoint("https://api.example.com/parse?url="))
        assertFalse(ParseRules.usesJsonEndpoint(""))
    }

    @Test
    fun `parse url appends signature and collapses double dot in json mode`() {
        val built = ParseRules.buildParseUrl(
            template = "https://api.example.com/..json?url=",
            vodUrl = "https://vod.example.com/1",
            apiKey = "sign",
            keyTime = "1700000000",
        )

        assertEquals(
            "https://api.example.com/.json?url=https://vod.example.com/1&apikey=sign&keytime=1700000000",
            built,
        )
    }

    @Test
    fun `parse url keeps template untouched for webview mode`() {
        val built = ParseRules.buildParseUrl(
            template = "https://api.example.com/parse?url=",
            vodUrl = "https://vod.example.com/1",
            apiKey = "sign",
            keyTime = "1700000000",
        )

        assertEquals(
            "https://api.example.com/parse?url=https://vod.example.com/1&apikey=sign&keytime=1700000000",
            built,
        )
    }

    @Test
    fun `play url without scheme is upgraded to https`() {
        assertEquals("https://cdn.example.com/1.m3u8", ParseRules.normalizePlayUrl("//cdn.example.com/1.m3u8"))
        assertEquals("https://cdn.example.com/1.m3u8", ParseRules.normalizePlayUrl("cdn.example.com/1.m3u8"))
    }

    @Test
    fun `play url with scheme is kept as is`() {
        assertEquals("http://cdn.example.com/1.m3u8", ParseRules.normalizePlayUrl("http://cdn.example.com/1.m3u8"))
        assertEquals("HTTPS://cdn.example.com/1.m3u8", ParseRules.normalizePlayUrl("HTTPS://cdn.example.com/1.m3u8"))
    }

    @Test
    fun `empty or null play url is rejected instead of becoming barehttps prefix`() {
        assertNull(ParseRules.normalizePlayUrl(null))
        assertNull(ParseRules.normalizePlayUrl(""))
        assertNull(ParseRules.normalizePlayUrl("   "))
    }

    @Test
    fun `scheme like httpx is not trusted as playable url`() {
        // 用 startsWith("http")，httpx:// 会被原样放行
        assertEquals(
            "https:httpx://evil.example.com/1.mp4",
            ParseRules.normalizePlayUrl("httpx://evil.example.com/1.mp4"),
        )
    }

    @Test
    fun `play url sniffing matches legacy markers`() {
        assertTrue(ParseRules.looksLikePlayUrl("https://cdn.example.com/a.m3u8"))
        assertTrue(ParseRules.looksLikePlayUrl("https://cdn.example.com/a.mp4?token=1"))
        assertTrue(ParseRules.looksLikePlayUrl("https://cdn.example.com/hls/m3u8?id=1"))
        assertTrue(ParseRules.looksLikePlayUrl("https://cdn.example.com/a.flv"))
        assertFalse(ParseRules.looksLikePlayUrl("https://cdn.example.com/index.html"))
        assertFalse(ParseRules.looksLikePlayUrl(null))
    }

    @Test
    fun `success code compares as string like the legacy backend`() {
        assertTrue(ParseRules.isSuccessCode("200"))
        assertTrue(ParseRules.isSuccessCode(" 200 "))
        assertFalse(ParseRules.isSuccessCode("0"))
        assertFalse(ParseRules.isSuccessCode(null))
    }

    @Test
    fun `next line index stops at the end of the list`() {
        assertEquals(1, ParseRules.nextLineIndex(currentIndex = 0, lineCount = 3))
        assertEquals(2, ParseRules.nextLineIndex(currentIndex = 1, lineCount = 3))
        assertNull(ParseRules.nextLineIndex(currentIndex = 2, lineCount = 3))
        assertNull(ParseRules.nextLineIndex(currentIndex = 0, lineCount = 0))
    }

    @Test
    fun `decryption key is host plus backend key`() {
        assertEquals(
            "api.example.comsecret-key",
            ParseRules.decryptionKey("https://api.example.com/", "secret-key"),
        )
        // 没有 scheme 时按原样取，避免一次 split 崩溃
        assertEquals("api.example.comsecret-key", ParseRules.decryptionKey("api.example.com", "secret-key"))
    }

    @Test
    fun `direct link patterns are full matches not contains`() {
        val patterns = listOf(".*\\.m3u8$", ".*\\.mp4$")

        assertTrue(ParseRules.matchesAnyPattern("https://cdn.example.com/a.m3u8", patterns))
        assertFalse(ParseRules.matchesAnyPattern("https://cdn.example.com/a.m3u8?token=1", patterns))
        assertFalse(ParseRules.matchesAnyPattern("", patterns))
        assertFalse(ParseRules.matchesAnyPattern("https://cdn.example.com/a.m3u8", emptyList()))
    }

    @Test
    fun `invalid backend regex is treated as no match instead of crashing`() {
        //  url.matches(it.toRegex()) 遇到坏正则直接抛 PatternSyntaxException
        assertFalse(ParseRules.matchesAnyPattern("https://cdn.example.com/a.m3u8", listOf("[unclosed")))
    }

    @Test
    fun `comma and double pipe separators are not interchangeable`() {
        assertEquals(listOf("youku", "qq"), ParseRules.splitByComma(" youku , qq ,, "))
        assertEquals(
            listOf("Referer", "User-Agent"),
            ParseRules.splitByHeaderSeparator("Referer||User-Agent||"),
        )
        assertEquals(emptyList<String>(), ParseRules.splitByHeaderSeparator(null))
    }

    @Test
    fun `dash type accepts relay urls without media suffix`() {
        val stream = ParseRules.normalizeStream(
            rawUrl = "https://relay.example.com/stream?p=bilibili&url=video",
            type = "dash",
            audioRaw = "https://relay.example.com/stream?p=bilibili&url=audio",
        )

        assertEquals("https://relay.example.com/stream?p=bilibili&url=video", stream?.url)
        assertEquals("https://relay.example.com/stream?p=bilibili&url=audio", stream?.audioUrl)
        assertEquals("dash", stream?.type)
        assertEquals(true, stream?.isDualStream)
    }

    @Test
    fun `suffix-less url without declared type is rejected as before`() {
        assertNull(ParseRules.normalizeStream("https://relay.example.com/stream?p=bilibili&url=video"))
    }

    @Test
    fun `single stream platforms keep working without audio`() {
        val stream = ParseRules.normalizeStream("https://cdn.example.com/1.m3u8", type = "m3u8")

        assertEquals("https://cdn.example.com/1.m3u8", stream?.url)
        assertNull(stream?.audioUrl)
        assertEquals(false, stream?.isDualStream)
    }

    @Test
    fun `type token is case insensitive and trimmed`() {
        assertEquals("dash", ParseRules.normalizeStream("https://relay.example.com/s?1", " DASH ")?.type)
    }

    @Test
    fun `broken audio degrades to video-only stream`() {
        val stream = ParseRules.normalizeStream("https://cdn.example.com/1.mp4", "dash", audioRaw = "  ")

        assertEquals("https://cdn.example.com/1.mp4", stream?.url)
        assertNull(stream?.audioUrl)
    }

    @Test
    fun `parse result referer is trimmed and kept on the stream`() {
        // B 站/爱奇艺 CDN 校验 Referer，解析站下发时必须原样往下传
        val stream = ParseRules.normalizeStream(
            rawUrl = "https://cdn.example.com/1.mp4",
            type = "dash",
            refererRaw = "  https://www.bilibili.com/  ",
        )

        assertEquals("https://www.bilibili.com/", stream?.referer)
        assertNull(ParseRules.normalizeStream("https://cdn.example.com/1.mp4", "dash", refererRaw = "  ")?.referer)
    }
}
