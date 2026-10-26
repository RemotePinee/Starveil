package com.starveil.feature.player

/**
 * 下一集播放地址的预取池（计划 §5 P3「预加载池」的最小可用版本）。
 *
 * 只缓存**归一化后的播放地址字符串**，不持有播放器实例。真正的三实例池
 * （current / next-prepared / recycle）要同时开三个播放器与三块视频面，
 * 内存与流量都翻倍，计划要求先有"连续滑 10 集不 OOM"的结论再上。
 * 所以这里只把接口留出来：将来 [take] 换成 `acquire(index): PreparedPlayer?`，
 * 调用点（[VerticalPlayViewModel.play]）不用动。
 *
 * 没有后台线程、没有失效定时器：短剧地址随详情一起下发，`load()` 换源或重试时
 * 由调用方 [clear]，一次播放会话内不会变。
 */
internal class VerticalPreloadPool(private val capacity: Int = PRELOAD_CAPACITY) {

    /** 插入顺序即预取顺序，满了先淘汰最旧的一条。 */
    private val urls = LinkedHashMap<Int, String>()

    /** 命中次数：切集时确实用上了预取结果的次数。 */
    var hits: Int = 0
        private set

    /** 未命中次数：首集、往回滑、自动连播跨过预取窗口时都会发生，不是错误。 */
    var misses: Int = 0
        private set

    val size: Int get() = urls.size

    /** 预取一集：[index] 是 pager 页号，[url] 是已归一化的地址。 */
    fun prefetch(index: Int, url: String) {
        urls[index] = url
        trim()
    }

    /** 取一条并出队：被消费的地址不留着，切回来时重新预取一次。 */
    fun take(index: Int): String? {
        val url = urls.remove(index)
        if (url == null) misses += 1 else hits += 1
        return url
    }

    fun contains(index: Int): Boolean = urls.containsKey(index)

    /** 换源 / 换剧 / 重试：整表作废，地址不跨会话保留。 */
    fun clear() = urls.clear()

    private fun trim() {
        while (urls.size > capacity) {
            val oldest = urls.keys.firstOrNull() ?: return
            urls.remove(oldest)
        }
    }
}

/** 缓存上限 3 条（计划 §5 P3）。只预取下一集时实际只用 1 条。 */
internal const val PRELOAD_CAPACITY = 3
