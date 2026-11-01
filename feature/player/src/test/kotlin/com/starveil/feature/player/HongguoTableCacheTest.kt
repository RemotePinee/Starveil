package com.starveil.feature.player

import com.starveil.core.hongguo.Mp4SampleTable
import com.starveil.core.hongguo.Sample
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内存缓存的两个上限：条数 8、估算字节 8MB，按访问顺序淘汰。
 *
 * 这条链路的崩溃形态是碎片化 OOM（`Failed to allocate a 24 byte allocation`），
 * 不是某次大分配失手，所以上限必须真的生效 —— 只靠"少存几条"的自觉，
 * 一刷起来就回到无界，之前 24 条封顶时的内存峰值就是这么来的。
 */
class HongguoTableCacheTest {

    @After
    fun tearDown() {
        HongguoTableCache.clear()
    }

    @Test
    fun `count cap evicts the oldest episode`() {
        repeat(10) { index -> HongguoTableCache.put("show/$index", prepared(index, samples = 1)) }

        assertEquals(8, HongguoTableCache.size())
        assertNull(HongguoTableCache.get("show/0"))
        assertNull(HongguoTableCache.get("show/1"))
        assertNotNull(HongguoTableCache.get("show/9"))
    }

    @Test
    fun `hit moves the entry to the tail so the next insert drops someone else`() {
        repeat(8) { index -> HongguoTableCache.put("show/$index", prepared(index, samples = 1)) }
        assertNotNull(HongguoTableCache.get("show/0"))

        HongguoTableCache.put("show/8", prepared(8, samples = 1))

        assertNotNull(HongguoTableCache.get("show/0"))
        assertNull(HongguoTableCache.get("show/1"))
    }

    @Test
    fun `byte cap refuses a table bigger than the whole budget`() {
        val huge = Mp4SampleTable(HugeSamples(200_000))

        HongguoTableCache.put("show/huge", HongguoPrepared(ByteArray(16), huge))

        assertEquals(0, HongguoTableCache.size())
        assertEquals(0L, HongguoTableCache.bytes())
    }

    @Test
    fun `one 4463 sample episode costs about 230KB so eight of them fit the budget`() {
        val episode = prepared(0, samples = 4463)

        assertTrue(episode.estimatedBytes in 220_000..250_000)
    }

    @Test
    fun `repeated put of the same episode does not double count its bytes`() {
        val episode = prepared(0, samples = 4463)

        HongguoTableCache.put("show/0", episode)
        HongguoTableCache.put("show/0", episode)

        assertEquals(1, HongguoTableCache.size())
        assertEquals(episode.estimatedBytes, HongguoTableCache.bytes())
    }

    private fun prepared(seed: Int, samples: Int): HongguoPrepared {
        val list = List(samples) { index -> Sample(seed * 1000 + index, 16, ByteArray(8)) }
        return HongguoPrepared(ByteArray(16), Mp4SampleTable(list))
    }
}

/**
 * 只需要 [size] 正确：真造二十万个 Sample 等于让测试自己也 OOM，
 * 而 [HongguoTableCache] 的字节上限只看条数换算出来的估算值。
 */
private class HugeSamples(override val size: Int) : AbstractList<Sample>() {
    override fun get(index: Int): Sample = Sample(index, 16, ByteArray(8))
}
