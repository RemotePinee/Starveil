package com.starveil.feature.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 播放引擎预热。
 *
 * `ExoPlayer.Builder(...).build()` 第一次要几百毫秒（MediaCodec 与轨道选择器的探测），
 * 冷启动后第一次进短剧正好撞上它：主线程一卡，表现就是"dock 已经切过去、页面还没进来"。
 *
 * 在启动阶段空跑一次再释放：探测结果进进程内静态缓存，之后真正要用的实例只要几十毫秒。
 * 实例本身不能留着复用——每个页面的播放会话都要自己的 surface、媒体源与播放位置。
 */
@OptIn(UnstableApi::class)
public object PlayerEngineWarmUp {

    private val finished = AtomicBoolean(false)
    /** 缓存初始化走 IO：开 SQLite、扫目录都是磁盘操作，压在首页滚动那一帧上会掉帧。 */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun warm(context: Context) {
        if (!finished.compareAndSet(false, true)) return
        // 分片缓存也在这里建：VideoCache 首次创建要开 SQLite 索引库、扫缓存目录算已用空间，
        // 几百毫秒。留到首次播放那一刻就是用户盯着"缓冲中"白等；挪到这里，用户在看首页。
        val app = context.applicationContext
        io.launch { runCatching { VideoCache.get(app) } }
        // ExoPlayer 只能在主线程构建，这一句留在调用线程上。
        runCatching { ExoPlayer.Builder(app).build().release() }
    }
}
