package com.starveil.core.data

import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json

/**
 * 页面快照：把上一次成功响应的 DTO 落到应用私有目录，冷启动时先拿它渲染首帧。
 *
 * 存 DTO 而不是领域模型：DTO 在 `:core:network` 已经全是 `@Serializable`，
 * 领域模型没有、也不该为此加序列化依赖。快照格式因此与接口字段天然一致，
 * 后端加字段只会被 `ignoreUnknownKeys` 忽略，不会让旧快照读不出来。
 *
 * 读是同步的（首帧就要用，几十 KB 解析在毫秒级），写一律切到 IO 线程；
 * 所有异常都吞成 null/空操作——快照坏掉最多是少一次秒开，绝不能让页面进不去。
 *
 * 版本号写在文件名里（`home_feed.v1.json`）：字段语义变了就把 [VERSION] 加一，
 * 旧文件自然读不到、也不需要迁移代码，比在文件里塞 schemaVersion 更省事。
 */
class SnapshotStore(
    private val dir: File,
    /**
     * 单线程写队列：同一份快照的多次写按调用顺序落地，不会互相覆盖成旧内容。
     *
     * 提成构造参数是为了测试能换成 `Dispatchers.Unconfined` 把写盘变成同步，
     * 不必靠 sleep 去等异步写。
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** 快照代次：登录/登出清空时加一，在途的写盘凭旧代次一律丢弃（见 [write]）。 */
    private val generation = AtomicLong(0L)

    /**
     * 同步读。只允许在首帧路径上调用，且单份快照要保持在几十 KB 量级。
     *
     * [maxAgeMs] 到期后当作没有快照。快照的失效不能只靠"下一次同参数请求成功"：
     * 请求失败、换了分类、用户不再刷新，旧快照就会一直替已删内容续命。
     * 写入时间是文件 mtime——写盘走"临时文件 + rename"，mtime 就是那次写入的时刻。
     */
    fun <T> read(
        name: String,
        deserializer: DeserializationStrategy<T>,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    ): T? {
        val file = file(name)
        return if (!file.isFile || isExpired(file, maxAgeMs)) {
            null
        } else {
            runCatching { json.decodeFromString(deserializer, file.readText()) }.getOrNull()
        }
    }

    /** 取不到 mtime 时按过期处理：宁可少一次秒开，也不铺一份来历不明的内容。 */
    private fun isExpired(file: File, maxAgeMs: Long): Boolean {
        if (maxAgeMs == KEEP_FOREVER) return false
        val writtenAt = file.lastModified()
        val ageMs = if (writtenAt <= 0L) Long.MAX_VALUE else System.currentTimeMillis() - writtenAt
        return ageMs > maxAgeMs
    }

    /**
     * 写盘异步到单线程 IO 队列：不阻塞调用线程，也不怕并发写乱序。
     *
     * 调用那一刻先记下 [generation]，真正落盘前再比对一次：中间若发生过登录/登出清空，
     * 这次写属于上一个账号，直接丢弃——否则在途的旧请求会把 A 的数据写回刚清干净的目录，
     * 换账号后 B 冷启动就先看到 A 的收藏与历史。
     */
    fun <T> write(name: String, serializer: SerializationStrategy<T>, value: T) {
        val payload = runCatching { json.encodeToString(serializer, value) }.getOrNull() ?: return
        val issuedAt = generation.get()
        scope.launch { writeNow(name, payload, issuedAt) }
    }

    private fun writeNow(name: String, payload: String, issuedAt: Long) {
        if (issuedAt != generation.get()) return
        // 先写临时文件再替换：进程被杀在中途时，旧快照仍然完整可用
        runCatching {
            dir.mkdirs()
            val temp = File(dir, "$name.v$VERSION.tmp")
            temp.writeText(payload)
            val target = file(name)
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
    }

    /** 删除单份快照：服务端明确说"没有这条数据"时用，别让旧快照替已删内容续命。 */
    fun remove(name: String) {
        val issuedAt = generation.get()
        scope.launch { removeNow(name, issuedAt) }
    }

    private fun removeNow(name: String, issuedAt: Long) {
        if (issuedAt != generation.get()) return
        runCatching { file(name).delete() }
    }

    /**
     * 按前缀保留最近修改的 [keep] 份快照，其余删掉。
     *
     * 详情页是"一片一个文件"，不清理会随浏览记录无限长；按 mtime 淘汰最省事，
     * 也不需要额外的索引文件。
     *
     * 真实文件名是 `name.v1.json`，匹配必须落在名字边界上（见 [matchesNamePrefix]）：
     * 原先的 `startsWith("$prefix.")` 永远匹配不到任何文件，淘汰从未生效过。
     */
    fun prune(prefix: String, keep: Int) {
        val issuedAt = generation.get()
        scope.launch { pruneNow(prefix, keep, issuedAt) }
    }

    private fun pruneNow(prefix: String, keep: Int, issuedAt: Long) {
        // 代次变了说明目录刚被清过：这次淘汰跑在旧账号的文件清单上，直接放弃
        if (issuedAt != generation.get()) return
        val files = runCatching {
            dir.listFiles { file -> matchesNamePrefix(file.name, prefix) }
        }.getOrNull()
        if (files == null || files.size <= keep) return
        files.sortedByDescending { it.lastModified() }.drop(keep).forEach { it.delete() }
    }

    /** `detail_` 要命中 `detail_123.v1.json`；反过来 `detail_1` 不能把 `detail_123` 一起吃进来。 */
    private fun matchesNamePrefix(fileName: String, prefix: String): Boolean {
        if (prefix.isEmpty() || !fileName.startsWith(prefix)) return false
        val endsAtBoundary = fileName.length == prefix.length || !fileName[prefix.length].isLetterOrDigit()
        return endsAtBoundary || !prefix.last().isLetterOrDigit()
    }

    private fun file(name: String) = File(dir, "$name.v$VERSION.json")

    /**
     * 推进代次并在后台清目录，返回的 Job 可以 join。
     *
     * 给"不能挂起的回调"（登录/登出的点击处理）用：代次在这一行**同步**推进，
     * 返回之后即使目录还没删完，在途请求的写盘也已经全部落空——"清完之前不许写"
     * 不依赖删除何时结束。suspend 调用方用 [clearAndAwait]。
     */
    fun clear(): Job {
        generation.incrementAndGet()
        return scope.launch { deleteAll() }
    }

    /** 推进代次并挂起等到目录真的删完：返回时磁盘已干净，不再有 fire-and-forget。 */
    suspend fun clearAndAwait() {
        generation.incrementAndGet()
        // 与写盘共用同一条单线程队列：先等已排队的写跑完，再删，删完才返回
        withContext(ioDispatcher) { deleteAll() }
    }

    private fun deleteAll() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    companion object {
        /**
         * 快照格式版本。改了任何快照里的字段语义（不只是加字段）就要加一，
         * 旧快照会被直接丢弃重来——静默沿用错格式才是最坏的结果。
         */
        const val VERSION = 1

        /** 内容型快照的默认寿命：一天。超过就当作没有，让网络重新决定内容。 */
        const val DEFAULT_MAX_AGE_MS = 24L * 60 * 60 * 1000

        /** 不过期：收藏、历史这类用户自己的数据不该因为放几天就消失，它们靠登录/登出清空。 */
        const val KEEP_FOREVER = Long.MAX_VALUE
    }
}
