package com.starveil.feature.player
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource

/**
 * ExoPlayer 薄封装：创建、装载、释放与状态回调，不含任何业务判断。
 *
 * 同时引了 ijk、阿里、系统三套内核（并各自带一份 so），这里只保留 Media3
 * HLS 走 [HlsMediaSource]，mp4/flv 走
 * [ProgressiveMediaSource]。请求头只在命中 issethead 时非空
 * （ParseRules.resolveHeaders），由调用方算好传入。
 *
 * **双流为什么用 [MergingMediaSource] 而不是 `DashMediaSource`**：
 * B 站解析返回的是音视频分离的两条**直链**（`type = "dash"`），但没有 `.mpd` 清单。
 * `DashMediaSource` 会按 MPD 解析，喂直链会抛 "Unsupported manifest"（解析失败），
 * 所以这里把两条 [ProgressiveMediaSource] 交给 [MergingMediaSource] 合并：
 * ExoPlayer 会同时拉两路、共用一条时间轴，seek/倍速只作用在合并后的 source 上。
 * 也因此不需要引入 `media3-exoplayer-dash` 依赖。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class Media3Player(context: Context) {

    /** 播放器回调。播放/暂停等业务语义由 ViewModel 决定，这里只报告事实。 */
    interface Listener {
        fun onPlaybackStateChanged(isBuffering: Boolean)
        fun onEnded()
        fun onError(message: String)
    }

    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        // 短剧是"短片段 + 高频切集"：起播门槛压到 1 秒，缓冲上限放到 30 秒，
        // 这样单集 1-3 分钟的内容能一次缓冲完，滑动时不再反复拉流。
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(/* minBufferMs = */ 8_000, /* maxBufferMs = */ 30_000, 1_000, 2_000)
                .build(),
        )
        .build()
    private val frames = Media3FrameState(player, DEFAULT_VIDEO_ASPECT_RATIO)

    /** 交给 Media3 ContentFrame 绑定输出；播放控制仍由本类的业务方法管理。 */
    val mediaPlayer: Player get() = player
    private var endedFramePositionMs: Long? = null

    private var listener: Listener? = null

    /** 上一次装载的地址：后台回来时用它重建媒体源（Surface 与解码器已被系统回收）。 */
    private var lastUrl: String? = null
    private var lastHeaders: Map<String, String> = emptyMap()
    private var lastAudioUrl: String? = null

    /** 是否停在错误态；后台掐断、切网、切集失败都会置位。 */
    private var errored = false

    /**
     * 用户想不想播。
     *
     * 之前 `prepare` 里硬写 `playWhenReady = true`：退后台被暂停后，正在缓冲的那次 prepare
     * 一完成就把声音放了回来（桌面还在响）。现在播放意图存这里，prepare 按它决定播不播。
     */
    private var desiredPlay = true

    /** 播放器是否处于错误态，恢复时据此决定要不要重建 source。 */
    val hasError: Boolean get() = errored

    /**
     * 当前视频的真实宽高比，用来自己算画面矩形。
     *
     * 不让 `PlayerView` 内部去居中：它的 `AspectRatioFrameLayout` 和我们的容器各算一套，
     * 一旦容器与窗口不完全重合，就会出现"上下左留黑、右边贴边"这种偏心。
     * 把比例拿出来，由 Compose 画一个 [androidx.compose.foundation.layout.aspectRatio]
     * 的 SurfaceView 并居中——矩形是我们自己的，锚点也是我们自己的。
     */
    val videoAspectRatio: Float get() = frames.aspectRatio

    /** 旧集帧只作合法过渡；新集首帧由带媒体身份的 Media3 渲染事件确认。 */
    internal val hasRenderedFrame: Boolean get() = frames.hasRenderedFrame
    internal val hasRenderedCurrentFrame: Boolean get() = frames.hasRenderedCurrentFrame

    private val mediaListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            listener?.onPlaybackStateChanged(playbackState == Player.STATE_BUFFERING)
            if (playbackState == Player.STATE_ENDED) listener?.onEnded()
            // STATE_READY = 第一条流已经到位，可以出画面：它就是"点了要等多久"的终点
            if (playbackState == Player.STATE_READY) recordStartup()
        }

        override fun onPlayerError(error: PlaybackException) {
            errored = true
            listener?.onError(error.errorCodeName)
        }
    }

    init {
        player.addListener(mediaListener)
        // 音频焦点 + 耳机拔出：不设属性时 ExoPlayer 默认 handleAudioFocus = false，
        // 来电/导航播报/别的 App 出声时它不会让路，声音会和对方叠在一起；
        // 拔耳机也因为没开 becoming-noisy 而继续从外放漏出来。
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        // media3 里"耳机拔出/蓝牙断开"就是这个开关（系统 becoming noisy 广播）
        player.setHandleAudioBecomingNoisy(true)
    }

    internal fun clearListener(expected: Listener) {
        if (listener === expected) listener = null
    }

    /** 当前播放位置；拖动进度条与 30 秒上报都读它。 */
    val currentPositionMs: Long get() = endedFramePositionMs ?: player.currentPosition

    /** 时长。未知时 Media3 返回 C.TIME_UNSET（负数），这里收敛成 0。 */
    val durationMs: Long get() = player.duration.coerceAtLeast(0L)

    fun setListener(value: Listener?) {
        listener = value
    }

    /**
     * 装载新地址。重复调用会替换媒体源，用于换线路/换集/试看拦截后复播。
     *
     * [audioUrl] 非空时按双流处理（B 站音视频分离）；为空走原来的单源路径，
     * 其它四家平台不受影响。两个地址相同则当作单流，避免重复合并同一路。
     *
     * 带 `Referer` 的线路（解析站下发的防盗链流）还要换 UA，原因见 [userAgentFor]。
     */
    fun prepare(url: String, headers: Map<String, String>, audioUrl: String? = null) {
        lastUrl = url
        lastHeaders = headers
        lastAudioUrl = audioUrl
        errored = false
        endedFramePositionMs = null
        // 起播计时起点：只在 prepare 时归零，seek/播放中不重复计时
        prepareStartedAt = SystemClock.elapsedRealtime()
        frames.beginSource()
        // 播放 HTTP 层：连接复用 + 磁盘缓存，细节见 [VideoHttp]
        val dataSource = videoHttp.dataSource(headers, userAgentFor(headers))
        val videoSource = buildSource(url, dataSource)
        val source = if (audioUrl.isNullOrBlank() || audioUrl == url) {
            videoSource
        } else {
            MergingMediaSource(videoSource, buildSource(audioUrl, dataSource))
        }
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = desiredPlay
    }

    /**
     * 用上一次的地址重建媒体源并停在 [positionMs]。
     *
     * 后台回来时 Surface 和硬件解码器都可能已被回收，Media3 有时停在错误态不再自愈；
     * 重建 source 是最省事、且不改播放语义的恢复方式。重建后保持暂停，
     * 播不播交给状态机（[PlayerUiState.isPlaying]）。
     */
    fun recoverIfNeeded(positionMs: Long) {
        // 错误态交给调用方重新解析，这里只处理"source 掉了"（IDLE）
        if (player.playbackState != Player.STATE_IDLE) return
        val url = lastUrl ?: return
        prepare(url, lastHeaders, lastAudioUrl)
        player.playWhenReady = desiredPlay
        player.seekTo(positionMs.coerceAtLeast(0L))
    }


    /**
     * 命中防盗链（带 `Referer`）时要替换的 UA；返回 null 表示沿用 Media3 默认 UA。
     *
     * B 站 `playurl` 是按 `platform=pc` 签发的，CDN 同时校验 Referer 与 UA：
     * UA 里只要带 `Android`（Media3 默认的 `ExoPlayerLib/1.9.1 (Linux;Android 16;…)` 也算），
     * 即使 Referer 正确也一律 403。同一地址实测：桌面 UA 206、Android UA 403。
     *
     * 所以带 Referer 且片源没显式配 `User-Agent` 时统一伪装成桌面 Chrome；
     * 没有 Referer 的普通直链保持 Media3 默认 UA，其它四家平台行为不变。
     */
    private fun userAgentFor(headers: Map<String, String>): String? {
        val hasReferer = headers.keys.any { it.equals(HEADER_REFERER, ignoreCase = true) }
        val hasUserAgent = headers.keys.any { it.equals(HEADER_USER_AGENT, ignoreCase = true) }
        return if (hasReferer && !hasUserAgent) DESKTOP_BROWSER_UA else null
    }

    /** 按地址后缀选 source：HLS 走 [HlsMediaSource]，其余按 progressive 处理。 */
    private fun buildSource(url: String, dataSource: DataSource.Factory): MediaSource {
        val item = MediaItem.Builder().setUri(Uri.parse(url)).setMediaId(frames.mediaId).build()
        return if (isHls(url)) {
            HlsMediaSource.Factory(dataSource)
                // 资源站的 m3u8 大多没有独立 init segment：允许 chunkless 准备，少一次请求
                .setAllowChunklessPreparation(true)
                .createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(dataSource).createMediaSource(item)
        }
    }

    fun seekTo(positionMs: Long) {
        endedFramePositionMs = null
        frames.output.acknowledgeSeek()
        player.seekTo(positionMs)
    }




    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }

    fun play() {
        desiredPlay = true
        player.playWhenReady = true
    }

    fun pause() {
        desiredPlay = false
        player.playWhenReady = false
    }

    fun release() {
        listener = null
        // 预取协程与缓存客户端一起收：进程里留一条后台下载没有意义
        videoHttp.release()
        frames.release()
        player.removeListener(mediaListener)
        player.release()
    }

    /** 起播计时起点；0 表示当前没有在等起播。 */
    private var prepareStartedAt = 0L

    /**
     * 记一次 `prepare → STATE_READY`。
     *
     * 这是"点了一下要等多久"的唯一口径：短剧竖滑时每次切集都会产生一条样本，
     * 用 P50/P90 看体验比平均值可信。
     */
    private fun recordStartup() {
        val started = prepareStartedAt
        if (started <= 0L) return
        prepareStartedAt = 0L
        PlayerStartupStats.record(SystemClock.elapsedRealtime() - started)
        // 性能基线取样：只在 logcat 可见，不属于业务日志
        Log.i("StarveilPlayer", PlayerStartupStats.summary().orEmpty()) // security-baseline-allow: 起播耗时基线
    }

    /** HLS 判定：带鉴权参数的地址以 `?` 结尾段，所以先截断再比后缀。 */
    private fun isHls(url: String): Boolean =
        url.substringBefore('?').endsWith(HLS_SUFFIX, ignoreCase = true)


    /**
     *
     * 具体的连接池、磁盘缓存与预取策略都在 [VideoHttp] 里，播放器只负责用它取数据源。
     */
    private val videoHttp = VideoHttp(context)

    /** 预取某一集：切集前把清单与首片落到磁盘缓存，切过去时少一次往返。 */
    fun prefetch(url: String?, headers: Map<String, String>) = videoHttp.prefetch(url, headers)

    private companion object {
        const val HLS_SUFFIX = ".m3u8"



        /** 还没收到画面尺寸时的兜底比例，与常见剧集一致。 */
        const val DEFAULT_VIDEO_ASPECT_RATIO = 16f / 9f

        /** 防盗链线路用的请求头键名；片源配置与解析站下发都按这个写法匹配（大小写不敏感）。 */
        const val HEADER_REFERER = "Referer"
        const val HEADER_USER_AGENT = "User-Agent"

        /** 桌面 Chrome 的 UA：B 站 CDN 只认非 Android 的 UA，见 [userAgentFor]。 */
        const val DESKTOP_BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
