package com.starveil.core.hongguo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HongguoRelayTest {

    @Test
    fun parsesRelayUrl() {
        val p = HongguoRelay.parse("https://hema.mnuk.cn/hongguo/7629637736885914686/3.mp4")
        assertEquals("7629637736885914686" to "3", p)
    }

    @Test
    fun parsesUrlWithQuery() {
        val p = HongguoRelay.parse("https://hema.mnuk.cn/hongguo/123/7.mp4?x=1")
        assertEquals("123" to "7", p)
    }

    @Test
    fun ignoresNonRelay() {
        assertNull(HongguoRelay.parse("https://cdn.example.com/a/b.mp4"))
        assertNull(HongguoRelay.parse(null))
    }

    @Test
    fun appendsMaterialFlag() {
        val plain = "https://hema.mnuk.cn/hongguo/1/2.mp4"
        assertEquals("$plain?d=1", HongguoRelay.materialUrl(plain))
        val withQuery = "$plain?x=1"
        assertEquals("$withQuery&d=1", HongguoRelay.materialUrl(withQuery))
    }

    @Test
    fun configMatchesOnlyWhenEnabledAndHit() {
        val cfg = HongguoConfig.parse(1, "hema.mnuk.cn,/hongguo/")
        assertTrue(cfg.matches("https://hema.mnuk.cn/hongguo/1/2.mp4"))
        assertFalse(cfg.matches("https://other.com/a.m3u8"))

        val off = HongguoConfig.parse(0, "hema.mnuk.cn")
        assertFalse(off.matches("https://hema.mnuk.cn/hongguo/1/2.mp4"))
    }
}
