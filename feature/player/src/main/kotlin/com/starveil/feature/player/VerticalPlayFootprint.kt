package com.starveil.feature.player

import coil3.SingletonImageLoader
import com.starveil.core.model.Vod

/**
 * 一次性诊断：一部剧装载成功后，这台进程为它背了多少 Java 堆。
 *
 * 只读：不改状态、不触发请求、不建对象图，唯一副作用是 debug 下多一行 logcat。
 * 目的是把「每部剧涨 27MB」从一个结论拆成几列可对照的数据——剧集地址太长、
 * 状态对象图太大、Coil 位图堆积、磁盘快照反序列化出的第二份副本，四者读数不同。
 *
 * 判读口径（`adb logcat -s HongguoGate`，只看 `mem vod=` 开头那行）：
 * - `eps` 是当前播放源的集数（状态里真正铺到 pager 的那些）；
 * - `srcEps` / `urls` 遍历 [Vod.playSources] 全部源，多源数据一并不漏；
 * - `vodSize` / `stateSize` 是 `toString()` 长度，只用于量级对比，不是字节数；
 * - `heap` 是 `totalMemory - freeMemory`，受 GC 时机影响，**看趋势不看单点**；
 * - `coil` 取 Coil 内存缓存 `size`；拿不到 context（单元测试）时为 `na`；
 * - `snap` 是 `cachedDetail(vodId).toString()` 长度，`-1` 表示这本剧没有快照。
 */
internal fun VerticalPlayViewModel.logLoadFootprint(vod: Vod) {
    PlayerLog.warn { footprintLine(vod) }
}

private fun VerticalPlayViewModel.footprintLine(vod: Vod): String {
    val state = stateFlow.value
    val runtime = Runtime.getRuntime()
    val maxMb = runtime.maxMemory() / MB
    return "mem vod=$vodId eps=${state.episodes.size}" +
        " srcEps=${vod.playSources.sumOf { it.episodes.size }}" +
        " urls=${urlCharsOf(vod)}" +
        " chars vodSize=${vod.toString().length} stateSize=${state.toString().length}" +
        " recs=na heap=${usedMb(runtime)}/$maxMb coil=${coilCacheMb()} snap=${snapshotChars()}"
}

/** 所有源的 url 总字符数：短剧是单源，但按源遍历才不会在换后端时漏量。 */
private fun urlCharsOf(vod: Vod): Int =
    vod.playSources.sumOf { source -> source.episodes.sumOf { it.url.length } }

private fun usedMb(runtime: Runtime): Long =
    (runtime.totalMemory() - runtime.freeMemory()) / MB

/**
 * Coil 内存缓存占用，单位 MB。
 *
 * context 从 [HongguoGateDataSource.appContext] 取——那是 [VideoHttp] 构造时写进来的
 * 同一个 applicationContext，短剧页的播放链路一定已经写过；单元测试里为 null，
 * 此时给 `na`，不为了打日志给 ViewModel 塞 Context 或改构造参数。
 */
private fun coilCacheMb(): String {
    val context = HongguoGateDataSource.appContext ?: return "na"
    val bytes = runCatching {
        SingletonImageLoader.get(context).memoryCache?.size ?: 0L
    }.getOrDefault(0L)
    return "${bytes / MB}MB"
}

/** 磁盘快照反序列化出来的那份对象有多大；`cachedDetail` 是纯查询，不写盘不发请求。 */
private fun VerticalPlayViewModel.snapshotChars(): Int = runCatching {
    vodRepository.cachedDetail(vodId)?.toString()?.length ?: -1
}.getOrDefault(-1)

private const val MB = 1024L * 1024L
