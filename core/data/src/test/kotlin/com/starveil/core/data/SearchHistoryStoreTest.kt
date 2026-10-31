package com.starveil.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索历史的去重、上限与清空规则。
 *
 * DataStore 用临时文件 + 独立 scope：不碰真实应用目录，也不依赖 Android Context。
 */
class SearchHistoryStoreTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dir: File = Files.createTempDirectory("search-history").toFile()

    private val dataStore = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { File(dir, "search_history.preferences_pb") },
    )

    private val store = DefaultSearchHistoryStore(dataStore)

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `blank keyword is ignored`() = runTest {
        store.add("")
        store.add("   ")

        assertTrue(store.cached().isEmpty())
    }

    @Test
    fun `duplicate keyword moves to front without duplicating`() = runTest {
        store.add("火影")
        store.add("海贼")
        val latest = store.add("火影")

        // 返回值就是最新列表，ViewModel 不必再读一次 cached()
        assertEquals(listOf("火影", "海贼"), latest)
        assertEquals(listOf("火影", "海贼"), store.cached())
    }

    @Test
    fun `oldest keyword falls off beyond the limit`() = runTest {
        repeat(SEARCH_HISTORY_LIMIT + 1) { index -> store.add("kw$index") }

        val history = store.cached()
        assertEquals(SEARCH_HISTORY_LIMIT, history.size)
        // 新的在前：最后加的那条排第一
        assertEquals("kw$SEARCH_HISTORY_LIMIT", history.first())
        // 最早那条被挤掉
        assertFalse(history.contains("kw0"))
    }

    @Test
    fun `clear empties history`() = runTest {
        store.add("火影")
        store.add("海贼")

        store.clear()

        assertTrue(store.cached().isEmpty())
        // 清空后还能继续记
        assertEquals(listOf("咒术"), store.add("咒术"))
    }

    @Test
    fun `fresh instance reloads history from disk`() = runTest {
        store.add("火影")

        // 模拟进程重启：新实例的内存副本是空的，只能从盘上把旧历史读回来
        val restarted = DefaultSearchHistoryStore(dataStore)

        assertEquals(listOf("海贼", "火影"), restarted.add("海贼"))
    }
}
