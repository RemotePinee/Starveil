package com.starveil.feature.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

/**
 * 缓冲窗口档位：四个门槛按内容形态分开，不再全应用共用一套。
 *
 * `DefaultLoadControl` 的四个参数，前两个是目标缓冲量，后两个是"什么时候开播/恢复播放"：
 * - `minBufferMs`：低于它继续拉流；
 * - `maxBufferMs`：高于它停止下载。**它同时决定网络抖动时能顶多久**；
 * - `bufferForPlaybackMs`：起播前至少攒多少；
 * - `bufferForPlaybackAfterRebufferMs`：卡顿之后重新攒够多少才继续播。
 *
 * 两档的差距来自两种内容的生命周期，不是调参偏好：
 * - 长剧一部两三小时、单集 45 分钟，一条源要连续播完，预读是唯一的抗抖动手段；
 * - 短剧竖屏一集几十秒、滑走即换源，且缓冲全落在 Java 堆上（`DefaultAllocator` 用
 *   byte[] 装数据），窗口按一集裁小，换剧时堆才不会摸到 256MB 上限。
 *
 * 曾经两档共用一套：为短剧页 OOM 把窗口压到 6/12 秒，长剧跟着只剩 12 秒预读，
 * 网络稍慢就 underrun → `STATE_BUFFERING` → 攒 3 秒 → 再播一两秒，表现正是
 * "每隔一两秒缓冲一次"。反过来给短剧留 50 秒预读就是那次 OOM。两种形态必须分开。
 */
@androidx.annotation.OptIn(UnstableApi::class)
enum class BufferingProfile(
    private val minBufferMs: Int,
    private val maxBufferMs: Int,
    private val bufferForPlaybackMs: Int,
    private val bufferForPlaybackAfterRebufferMs: Int,
) {
    /** 长剧：30 秒预读、50 秒封顶，恢复播放要等 5 秒余量——宁可多等半秒，不要一卡一卡。 */
    LongForm(
        minBufferMs = 30_000,
        maxBufferMs = 50_000,
        bufferForPlaybackMs = 2_500,
        bufferForPlaybackAfterRebufferMs = 5_000,
    ),

    /** 短剧竖屏：窗口压到一集以内，堆上常驻缓冲 6MB 上下，滑走换源不累积。 */
    ShortForm(
        minBufferMs = 6_000,
        maxBufferMs = 12_000,
        bufferForPlaybackMs = 1_500,
        bufferForPlaybackAfterRebufferMs = 3_000,
    ),
    ;

    /** 每次建播放器各建一份：`DefaultLoadControl` 带可变状态，不能跨播放器复用。 */
    fun newLoadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            minBufferMs,
            maxBufferMs,
            bufferForPlaybackMs,
            bufferForPlaybackAfterRebufferMs,
        )
        .build()
}
