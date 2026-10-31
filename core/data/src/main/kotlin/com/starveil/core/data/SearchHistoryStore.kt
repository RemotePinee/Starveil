package com.starveil.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 搜索历史：最近搜过的关键词，新的在前。
 *
 * 与其它仓库接口同一套务实约定：方法一律带默认实现，其它 feature 的手写 fake 不必补。
 */
interface SearchHistoryStore {

    /** 内存里的当前值，给首帧直接铺，不用等 DataStore 读盘。 */
    fun cached(): List<String> = emptyList()

    /** 记一条；空白串直接忽略，重复词提到最前，超过上限丢最旧的。返回最新列表。 */
    suspend fun add(keyword: String): List<String> = cached()

    suspend fun clear() = Unit
}

/** 上限 10 条：再多就是列表，不是"最近"。 */
const val SEARCH_HISTORY_LIMIT: Int = 10

/**
 * DataStore 实现。
 *
 * 存成一个 JSON 数组字符串，而不是 `StringSet`：历史是有序的（新的在前、旧的先丢），
 * `StringSet` 读回来顺序不确定，等于把排序丢掉。
 *
 * [cached] 读 `@Volatile` 内存副本：构造时不同步读盘（DataStore 的读是 suspend），
 * 只在后台预热一次；此后每次 [add]/[clear] 落盘后原地更新副本。
 * 读写一律在 [Dispatchers.IO] 上，与 [PreferenceSessionStore] 一致。
 *
 * 与会话共用一个 `DataStore<Preferences>` 文件（容器传的是 `sessionDataStore`）：
 * 登出时 [PreferenceSessionStore.clear] 清整个文件会顺手清掉历史——历史没有账号归属，
 * 换人换号后清掉反而是对的。
 */
class DefaultSearchHistoryStore(
    private val dataStore: DataStore<Preferences>,
) : SearchHistoryStore {

    /**
     * 预热与落盘共用的作用域。
     *
     * 容器里是进程级单例，生命周期与 Application 相同，不单独取消；
     * 换成调用方传进来的 scope 只会让每次构造都要多传一个参数。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 串行化读盘、落盘与副本更新：预热的结果不能盖掉刚写进去的新词。 */
    private val lock = Mutex()

    @Volatile
    private var memory: List<String> = emptyList()

    init {
        // 只启动后台预热，构造线程不等盘；进程重启后 cached() 很快就会拿到上次的历史
        scope.launch {
            lock.withLock {
                if (memory.isEmpty()) memory = readFromDisk()
            }
        }
    }

    override fun cached(): List<String> = memory

    override suspend fun add(keyword: String): List<String> {
        val word = keyword.trim()
        if (word.isEmpty()) return cached()
        return withContext(Dispatchers.IO) {
            lock.withLock {
                // 副本还空有两种可能：进程刚起来预热没跑完、用户刚清空过。两种情况都以盘为准
                val base = if (memory.isEmpty()) readFromDisk() else memory
                val next = (listOf(word) + base.filterNot { it == word }).take(SEARCH_HISTORY_LIMIT)
                write(next)
                next
            }
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            lock.withLock {
                runCatching {
                    dataStore.edit { prefs -> prefs.remove(KEY_HISTORY) }
                }
                memory = emptyList()
            }
        }
    }

    /** 落盘失败只影响下次冷启动：副本已经更新，本次会话里界面照常显示。 */
    private suspend fun write(history: List<String>) {
        memory = history
        runCatching {
            dataStore.edit { prefs ->
                prefs[KEY_HISTORY] = JSON.encodeToString(SERIALIZER, history)
            }
        }
    }

    /** 读盘失败（文件损坏 / IO 错误）按"没有历史"处理：历史坏掉不该让搜索页进不去。 */
    private suspend fun readFromDisk(): List<String> {
        val raw = runCatching { dataStore.data.first()[KEY_HISTORY] }.getOrNull()
            ?: return emptyList()
        return runCatching { JSON.decodeFromString(SERIALIZER, raw) }.getOrElse { emptyList() }
    }

    private companion object {
        /** 命名与 [PreferenceSessionStore] 的键一致：用途前缀 + 小写下划线。 */
        val KEY_HISTORY = stringPreferencesKey("search_history")

        val SERIALIZER = ListSerializer(String.serializer())

        val JSON = Json
    }
}
