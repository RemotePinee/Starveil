package com.starveil.feature.home

import com.starveil.core.model.Vod
import org.junit.Assert.assertEquals
import org.junit.Test

/** 首页封面预热队列：只测「排哪些图」，下载本身在组合层，靠真机看。 */
class HomeCoverWarmupTest {

    @Test
    fun `same poster is queued once and blank posters are skipped`() {
        val warmup = HomeCoverWarmup()

        warmup.add(listOf(sampleVod(1, "https://img.example.com/1.jpg"), sampleVod(2, null), sampleVod(3, " ")))
        warmup.add(listOf(sampleVod(4, "https://img.example.com/1.jpg"), sampleVod(5, "https://img.example.com/5.jpg")))

        assertEquals(
            listOf("https://img.example.com/1.jpg", "https://img.example.com/5.jpg"),
            warmup.urls.value,
        )
    }

    @Test
    fun `a batch only queues the first screen of posters`() {
        val warmup = HomeCoverWarmup()

        warmup.add((1..12).map { sampleVod(it, "https://img.example.com/$it.jpg") })

        assertEquals(6, warmup.urls.value.size)
    }
}

private fun sampleVod(id: Int, poster: String?) = Vod(
    id = id,
    name = "影片 $id",
    poster = poster,
    remarks = null,
    year = null,
    area = null,
    score = null,
    blurb = null,
    playSources = emptyList(),
)
