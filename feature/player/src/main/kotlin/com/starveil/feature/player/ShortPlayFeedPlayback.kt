package com.starveil.feature.player

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 短剧 feed 的唯一播放器槽位。
 *
 * 一个实例 = 一台 [Media3Player] + 一条 prepare 身份。短剧页曾给**每部刷过的剧**各建一个，
 * 滑 50 屏就攒 50 套硬件解码器：`mId` 涨到 16、高通组件累计分配 839 个、native 838MB
 * 而 Java 堆只有 256MB，最后 OOM 在 `MediaCodec.getBuffer`。
 *
 * 现在整条链路只有这一个创建点（[:app] 的 `ShortPlayEntry` 用 [rememberShortPlayFeedPlayback]
 * 建一次）。滑页只换媒体项：`setMediaItem` → `prepare` → `playWhenReady`。
 *
 * 画面归落页那一屏**自己的页面**画（见 `VerticalPlayScreen` 的 `hostsVideo`）：feed 只把同一台
 * 引擎交给每一页，页面把画面放在自己的封面之上、入口条与右栏之下。画面与页面是同一个布局
 * 节点，滑页时两者的矩形由构造保证一致，没有第二处需要对齐的坐标。
 */
class ShortPlayFeedPlayback internal constructor(context: Context) {

    internal val owner = VerticalPlaybackOwner(context.applicationContext) { true }

    /** 广告整屏盖住播放面：还解码器但保留播放器，回到正片重新 prepare。 */
    fun stopForCover() = owner.session.stopForCover()

    fun release() = owner.release()
}

/**
 * feed 级播放器：一个短剧页一次，`remember` + `DisposableEffect` 释放。
 *
 * 为什么不放进 ViewModel：VM 的寿命是整个短剧路由（含切 tab、后台），
 * 把它当播放器宿主等于让引擎活得比页面久，这正是"暂离不释放"变成泄漏的那条路。
 */
@Composable
fun rememberShortPlayFeedPlayback(): ShortPlayFeedPlayback {
    val context = LocalContext.current.applicationContext
    val playback = remember(context) { ShortPlayFeedPlayback(context) }
    DisposableEffect(playback) { onDispose { playback.release() } }
    return playback
}
