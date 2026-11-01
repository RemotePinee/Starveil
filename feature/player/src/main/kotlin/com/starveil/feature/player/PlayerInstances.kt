package com.starveil.feature.player

import android.os.Process
import java.util.concurrent.atomic.AtomicInteger

/**
 * 存活播放器计数（只在 debug 打点，release 下 [PlayerLog] 首行就返回）。
 *
 * 为什么要有它：短剧页曾因硬件解码器堆积而 OOM——`mId` 一路涨到 16、高通组件分配器
 * 累计分配 839 个解码器组件、RSS 838MB 而 Java 堆只有 256MB。堆栈落在
 * `MediaCodec.getBuffer`，说明内存大头在 native，看 Java 堆永远看不出问题。
 * 一个播放器实例 = 一套解码器 + 一组加载/播放线程 + 一条 HTTP 连接池，
 * 所以"现在还有几个播放器活着"是这类崩溃唯一直接的观测口径。
 *
 * 判读方式（`adb logcat -s HongguoGate`）：进短剧页刷 50 条，[aliveCount] **恒为 1**，
 * 不随滑动次数增长；整条 feed 共用一台播放器（`ShortPlayFeedPlayback`），所以
 * `created` 也该停在 1。只要滑页时 `created` 继续涨，就说明又有人按"一屏一台"建引擎了：
 * 换剧/换集只换媒体项（`setMediaSource` + `prepare`），`alive` 不动，`created` 也不动。
 */
internal object PlayerInstances {

    private val alive = AtomicInteger(0)
    private val created = AtomicInteger(0)
    private val released = AtomicInteger(0)

    /** 当前存活的播放器数；只在测试与日志里读。 */
    val aliveCount: Int get() = alive.get()

    /** 累计创建数：与 [aliveCount] 一起看能区分"换页"和"泄漏"。 */
    val createdCount: Int get() = created.get()

    fun onCreate() {
        val now = alive.incrementAndGet()
        val total = created.incrementAndGet()
        PlayerLog.warn { "player +1 alive=$now created=$total released=${released.get()} pid=${Process.myPid()}" }
    }

    fun onRelease() {
        val now = alive.decrementAndGet().coerceAtLeast(0)
        val total = released.incrementAndGet()
        PlayerLog.warn { "player -1 alive=$now created=${created.get()} released=$total pid=${Process.myPid()}" }
    }
}
