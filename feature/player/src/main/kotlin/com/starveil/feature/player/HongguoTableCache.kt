package com.starveil.feature.player

import com.starveil.core.hongguo.Mp4SampleTable
import java.util.LinkedHashMap

/** 一集解密就绪的材料：16 字节的 spade_a 派生密钥 + sample 表。 */
internal class HongguoPrepared(val key: ByteArray, val table: Mp4SampleTable) {

    /**
     * 这一份在堆上的估算占用。
     *
     * 每条样本两个小对象：Sample 本身 24 字节、8 字节 IV 的数组 24 字节，
     * 再加 ArrayList 的 4 字节槽位 —— 4463 条的一集约 230KB。
     * 崩溃日志里 "Failed to allocate a 24 byte allocation" 点的正是这两种对象，
     * 所以缓存要按字节算，不能只按条数算。
     */
    val estimatedBytes: Long = table.samples.size * BYTES_PER_SAMPLE + BYTES_PER_ENTRY

    private companion object {
        /** Sample 24 + IV 数组 24 + 列表槽位 4。 */
        const val BYTES_PER_SAMPLE = 52L

        /** key 数组与包装对象本身的固定开销。 */
        const val BYTES_PER_ENTRY = 128L
    }
}

/**
 * 内存里的解密材料缓存：「剧/集」-> 密钥 + sample 表。
 *
 * **为什么必须封顶**：一集 4463 条的 sample 表在堆上约 230KB，只按条数封 24 条就是
 * 5.5MB；更糟的是这 5.5MB 全由 24 字节的小对象拼成，正好把 ART 的 region space
 * 切碎 —— 崩溃现场是 `Failed to allocate a 24 byte allocation` +
 * `Number of 256KB sized free regions are: 0`，不是一次大分配失手。
 *
 * **双上限**：条数 8（滚动窗口只需要「当前一集 + 预取的两集 + 回滑几集」）与
 * 估算字节 8MB（挡住 moov 特别大、采样点两万条以上的集子）。两个条件哪个先到都淘汰，
 * 按访问顺序淘汰最久没用的那一集。
 *
 * **淘汰不打断正在播放的那一集**：[HongguoCencDataSource] 自己持有 table 引用，
 * 这里丢掉的只是"下一次 open 能直接拿来用"的那一份；下一次 open 落到
 * [HongguoDiskCache] 接住，不会因为内存没命中就重下 800KB 头部。
 *
 * **为什么用 synchronized 而不是 ConcurrentHashMap**：LRU 要按访问顺序重排链表，
 * ConcurrentHashMap 排不了；而 `LinkedHashMap(accessOrder = true)` 自己不是线程安全的
 * ——open() 在播放线程、预取在两个预取线程，同时读写会丢项甚至死循环。
 * 锁的范围只是几次 map 操作，不在锁里做 IO 与解析。
 */
internal object HongguoTableCache {

    /** 条数上限：当前一集 + 预取两集 + 回滑几集。 */
    private const val MAX_ENTRIES = 8

    /**
     * 字节上限。单条上限按估算值算：8MB 约等于 16 万条采样点，
     * 常态的一集（4463 条、约 230KB）永远碰不到，它只拦 moov 异常大的集子。
     */
    private const val MAX_BYTES = 8L * 1024 * 1024

    private val lru = LinkedHashMap<String, HongguoPrepared>(16, 0.75f, true)

    /** 缓存里的估算字节数；只跟 [lru] 一起在锁内改。 */
    private var cachedBytes = 0L

    fun get(tag: String): HongguoPrepared? = synchronized(lru) { lru[tag] }

    fun put(tag: String, prepared: HongguoPrepared): Unit = synchronized(lru) {
        lru.put(tag, prepared)?.let { cachedBytes -= it.estimatedBytes }
        cachedBytes += prepared.estimatedBytes
        // 条数与字节谁先超都淘汰；先超的那个会把缓存拉回上限内，循环必然结束
        while (lru.size > MAX_ENTRIES || cachedBytes > MAX_BYTES) {
            val eldest = lru.keys.firstOrNull() ?: break
            lru.remove(eldest)?.let { cachedBytes -= it.estimatedBytes }
        }
    }

    /** 当前条数。 */
    fun size(): Int = synchronized(lru) { lru.size }

    /** 当前估算字节。 */
    fun bytes(): Long = synchronized(lru) { cachedBytes }

    /** 缓存现状，只用于日志。 */
    fun describe(): String = synchronized(lru) { "${size()} 集 / ${bytes() / 1024}KB" }

    /** 丢掉全部内存副本（磁盘那份保留）：显式刷新与单测都走这里。 */
    fun clear(): Unit = synchronized(lru) {
        lru.clear()
        cachedBytes = 0
    }
}
