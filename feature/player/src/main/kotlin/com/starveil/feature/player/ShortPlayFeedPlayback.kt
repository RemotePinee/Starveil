package com.starveil.feature.player

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clipToBounds

/** 视频面归谁画。[FEED] = 整个 feed 唯一一份，页面只画覆盖层；[PAGE] = 本页自己画。 */
enum class ShortPlayVideoHost { PAGE, FEED }

/**
 * 短剧 feed 的唯一播放器槽位。
 *
 * 一个实例 = 一台 [Media3Player] + 一个 TextureView + 一条 prepare 身份。短剧页曾给
 * **每部刷过的剧**各建一个，滑 50 屏就攒 50 套硬件解码器：`mId` 涨到 16、高通组件
 * 累计分配 839 个、native 838MB 而 Java 堆只有 256MB，最后 OOM 在 `MediaCodec.getBuffer`。
 *
 * 现在整条链路只有这一个创建点（[:app] 的 `ShortPlayEntry` 用 [rememberShortPlayFeedPlayback]
 * 建一次）。滑页只换媒体项：`setMediaItem` → `prepare` → `playWhenReady`，
 * 视频面从头到尾不动，所以换源时不会闪黑，也不会有第二个解码器。
 */
class ShortPlayFeedPlayback internal constructor(context: Context) {

    internal val owner = VerticalPlaybackOwner(context.applicationContext) { true }

    /**
     * 当前落页交给 feed 级视频层的渲染输入。
     *
     * 视频面在 pager **下面**只有一份，但它的平移必须跟着"正在播的那一页"的内层 pager
     * （展开全集后就是逐集 pager）：这一页把自己的 [VerticalPlayStage] 写进来，
     * 离场时按令牌撤回，不会把已销毁页的状态留在槽位上。
     */
    internal var stage: VerticalPlayStage? by mutableStateOf(null)
        private set

    private var stageToken: Any? = null

    internal fun publish(token: Any, stage: VerticalPlayStage) {
        stageToken = token
        this.stage = stage
    }

    internal fun unpublish(token: Any) {
        if (stageToken !== token) return
        stageToken = null
        stage = null
    }

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

/**
 * feed 的唯一视频输出，铺在 pager 下面。
 *
 * 合成身份不随滑动、换剧、换集改变：`VideoStage` 里的 `ContentFrame` 只认这一台引擎，
 * 所以整页只创建一个 TextureView/SurfaceTexture，换源时复用同一个。
 * 视频层自带的整页底跟着"有没有首帧"切换：起播/换源这一段铺同一部剧的模糊封面
 * （片源比容器宽时 Fit 会在上下留条，那两条必须由封面填住，否则滑页时露出的就是纯黑），
 * 出首帧之后换成纯深色——播放起来还挂着模糊背景，视觉上就是脏的。
 *
 * [covered] 为真是"当前落页是整屏广告"：停掉内容解码（不释放），
 * 回到正片时由那一页的会话重新 prepare 同一条地址。
 */
@Composable
fun ShortPlayFeedVideoLayer(
    playback: ShortPlayFeedPlayback,
    covered: Boolean,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(playback, covered) { if (covered) playback.stopForCover() }
    val stage = playback.stage
    // 请求的源还没出帧 = 起播中 / 换源中 / 首帧之前，这期间底和视频矩形都只该是封面。
    val hasFrame = stage?.hasCurrentFrame == true
    Box(
        // 布局尺寸仍是整屏：画面要按整屏取景；越出边界的那部分（TextureView 的输出、
        // 内层 pager 换集时的平移）靠这层裁掉，不能被画到布局边界外。
        modifier = modifier.fillMaxSize().clipToBounds(),
    ) {
        // 整页底：锚点页同一部剧的封面，铺满整页（ContentScale.Crop）。视频仍然 Fit 居中、
        // 一帧不裁，上下的空档由这张底接住；出首帧后这里换成纯深色，不再模糊。
        // 底只跟 feed 的滑页平移走（外层 FeedVideoLayer 已经加了那一段），不跟内层换集平移：
        // 换集时视频走一屏、底不动，空出来的位置仍是同一部剧的封面。
        VerticalPlaybackBackdrop(cover = stage?.state?.cover, hasFrame = hasFrame)
        Box(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                translationY = stage?.videoTranslationY() ?: 0f
            },
        ) {
            VideoStage(engine = playback.owner.engine, cover = stage?.state?.cover, hasFrame = hasFrame)
        }
    }
}
