package com.starveil.feature.player

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 启动期预热。
 *
 * 目前只预热分片缓存（[VideoCache]）：它的首次创建要开 SQLite 索引库、扫缓存目录，
 * 几百毫秒。留到首次播放那一刻就是用户盯着"缓冲中"白等，挪到启动后空闲时就没有感知。
 *
 * 这里曾经还会建一个 `ExoPlayer` 再立刻 `release()`，指望把 MediaCodec 探测结果留在
 * 进程内。实测对首次起播没有可见效果——渲染器和解码器在 `release()` 时就被销毁了，
 * 真正创建时还得重来；而 build/release 这一下会初始化和拆除音频输出，
 * 表现是进首页时偶尔"咔"一声。要真预热只能留实例复用，那牵扯播放器所有权，另说。
 */
public object PlayerEngineWarmUp {

    private val finished = AtomicBoolean(false)

    /** 缓存初始化走 IO：开 SQLite、扫目录都是磁盘操作，压在首页滚动那一帧上会掉帧。 */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun warm(context: Context) {
        if (!finished.compareAndSet(false, true)) return
        val app = context.applicationContext
        io.launch { runCatching { VideoCache.get(app) } }
    }
}
