package com.starveil.feature.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.atomic.AtomicBoolean

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

    fun warm(context: Context) {
        if (!finished.compareAndSet(false, true)) return
        // 预热失败无所谓：真正的实例照样会建，只是第一次慢一点。
        runCatching { ExoPlayer.Builder(context.applicationContext).build().release() }
    }
}
