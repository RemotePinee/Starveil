package com.starveil.feature.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import java.util.concurrent.atomic.AtomicReference

/** 只记录实际渲染到当前媒体源的帧；不截屏、不落盘、不跨播放器共享。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class Media3FrameState(private val player: ExoPlayer, initialAspectRatio: Float) : AnalyticsListener {
    private var revision = 0L
    private var pendingAspectRatio = initialAspectRatio
    internal val output = Media3OutputFrame()
    private var receivedCurrentFrame = false
    var hasRenderedCurrentFrame: Boolean by mutableStateOf(false)
        private set
    private var lastRenderedSurface: Any? = null
    private var retiredSurface: Any? = null
    private val framePosition = AtomicReference<Pair<Long, Long>?>(null)
    private var metadataListener: VideoFrameMetadataListener? = null
    var isReleased = false
        private set
    val lastFramePositionMs: Long?
        get() = framePosition.get()?.takeIf { it.first == revision && it.second >= 0L }?.second?.div(1_000L)
    var mediaId: String = ""
        private set
    var aspectRatio: Float by mutableStateOf(initialAspectRatio)
        private set
    val hasRenderedFrame: Boolean get() = hasRenderedCurrentFrame

    init {
        player.addAnalyticsListener(this)
    }

    fun beginSource() {
        revision += 1L
        mediaId = "frame-$revision"
        hasRenderedCurrentFrame = false
        receivedCurrentFrame = false
        framePosition.set(null)
        val source = revision
        val metadata = VideoFrameMetadataListener { timeUs, _, _, _ ->
            framePosition.set(source to timeUs)
        }
        metadataListener = metadata
        player.setVideoFrameMetadataListener(metadata)
    }

    fun bindOutput(texture: Any) {
        output.bind(texture)
        retiredSurface = lastRenderedSurface
    }

    /** 纹理留下、输出没了：重挂时必须重新等首帧，并允许一次合法重绘。 */
    fun detachOutput(texture: Any) {
        if (output.detach(texture)) retiredSurface = lastRenderedSurface
    }

    fun unbindOutput(texture: Any) {
        if (output.unbind(texture)) retiredSurface = lastRenderedSurface
    }

    override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
        if (!isCurrent(eventTime)) return
        val ratio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
        if (!ratio.isFinite() || ratio <= 0f) return
        pendingAspectRatio = ratio
        // 下一集首帧前保持旧帧原比例，不能先用新集尺寸拉伸旧画面。
        if (receivedCurrentFrame) aspectRatio = ratio
    }

    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
        if (!isCurrent(eventTime)) return
        lastRenderedSurface = output
        receivedCurrentFrame = true
        hasRenderedCurrentFrame = true
        aspectRatio = pendingAspectRatio
        this.output.rendered()
    }

    fun release() {
        isReleased = true
        player.removeAnalyticsListener(this)
        metadataListener?.let(player::clearVideoFrameMetadataListener)
        metadataListener = null
        framePosition.set(null)
        lastRenderedSurface = null
        retiredSurface = null
    }

    private fun isCurrent(eventTime: AnalyticsListener.EventTime): Boolean {
        val timeline = eventTime.timeline
        if (eventTime.windowIndex !in 0 until timeline.windowCount) return false
        return timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId == mediaId
    }
}
