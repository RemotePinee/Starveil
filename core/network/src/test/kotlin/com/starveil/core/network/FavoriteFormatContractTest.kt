package com.starveil.core.network

import com.starveil.core.network.dto.FavoriteDto
import com.starveil.core.network.mapper.toDomain
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 收藏接口的两代格式。
 *
 * 插件现在返回扁平结构（`vod_id` / `title` / `poster` / `created_at`），
 * 旧 macCMS 返回嵌套 `data`。两种都必须解析出正确的影片 id 与标题——
 * 否则收藏页会出现"影片已下架"，且所有条目 id 都是 0（取消一条看起来取消了一列）。
 */
class FavoriteFormatContractTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `flat payload keeps vod id title poster and time`() {
        val body = """
            {"ulog_id":31,"vod_id":124,"title":"小黄人与大怪兽",
             "poster":"https://img.example.com/124.jpg","created_at":1792468020}
        """.trimIndent()

        val item = json.decodeFromString<FavoriteDto>(body).toDomain()

        assertEquals(31, item.ulogId)
        assertEquals(124, item.vodId)
        assertEquals("小黄人与大怪兽", item.title)
        assertEquals("https://img.example.com/124.jpg", item.poster)
        assertEquals(1_792_468_020L, item.createdAt)
    }

    @Test
    fun `legacy nested payload still maps`() {
        val body = """
            {"ulog_id":7,"ulog_rid":88,"ulog_time":1600000000,
             "data":{"id":88,"name":"旧格式影片","pic":"https://img.example.com/88.jpg"}}
        """.trimIndent()

        val item = json.decodeFromString<FavoriteDto>(body).toDomain()

        assertEquals(88, item.vodId)
        assertEquals("旧格式影片", item.title)
        assertEquals("https://img.example.com/88.jpg", item.poster)
        assertEquals(1_600_000_000L, item.createdAt)
    }

    @Test
    fun `missing vod data falls back to ulog rid without blank title`() {
        val body = """{"ulog_id":9,"ulog_rid":55,"title":"只有扁平标题"}"""

        val item = json.decodeFromString<FavoriteDto>(body).toDomain()

        assertEquals(55, item.vodId)
        assertEquals("只有扁平标题", item.title)
    }
}
