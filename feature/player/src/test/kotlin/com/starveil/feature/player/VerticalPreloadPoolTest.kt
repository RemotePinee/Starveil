package com.starveil.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerticalPreloadPoolTest {

    @Test
    fun `take returns the prefetched url and counts a hit`() {
        val pool = VerticalPreloadPool()

        pool.prefetch(index = 1, url = "https://cdn.example.com/dj-2.m3u8")

        assertEquals("https://cdn.example.com/dj-2.m3u8", pool.take(1))
        assertEquals(1, pool.hits)
        assertEquals(0, pool.misses)
    }

    @Test
    fun `take consumes the entry so it is fetched again next time`() {
        val pool = VerticalPreloadPool()
        pool.prefetch(index = 1, url = "https://cdn.example.com/dj-2.m3u8")

        pool.take(1)

        assertFalse(pool.contains(1))
        assertNull(pool.take(1))
        assertEquals(1, pool.misses)
    }

    @Test
    fun `capacity keeps only the newest three urls`() {
        val pool = VerticalPreloadPool()
        (0..3).forEach { index -> pool.prefetch(index = index, url = "https://cdn.example.com/dj-$index.m3u8") }

        assertEquals(PRELOAD_CAPACITY, pool.size)
        assertFalse(pool.contains(0))
        assertTrue(pool.contains(1))
        assertTrue(pool.contains(3))
    }

    @Test
    fun `clear drops every url and is safe on an empty pool`() {
        val pool = VerticalPreloadPool()
        pool.prefetch(index = 1, url = "https://cdn.example.com/dj-2.m3u8")

        pool.clear()
        pool.clear()

        assertEquals(0, pool.size)
        assertNull(pool.take(1))
    }
}
