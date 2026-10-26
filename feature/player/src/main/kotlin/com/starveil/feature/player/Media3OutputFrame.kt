package com.starveil.feature.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.lang.ref.WeakReference

/**
 * 输出身份不等于媒体身份：纹理销毁后历史首帧不能继续作为当前输出有画面的依据。
 *
 * 一次"输出会话"= 纹理挂到某个 View 上的一次生命周期。同一块 [android.graphics.SurfaceTexture]
 * 被保留下来再挂到新 View（暂离返回、配置变更）时 ExoPlayer 会重建 `Surface` 与解码器，
 * 帧事件从零开始；所以 [bind] 每次都按新会话处理，不能因为纹理对象是同一个就跳过。
 */
internal class Media3OutputFrame {
    private var identity: Any? = null
    private var detached: Any? = null
    private var redrawGeneration = -1L
    private var rendererReady = false
    private var textureUpdated = false
    var generation: Long by mutableLongStateOf(0L)
        private set
    var hasFrame: Boolean by mutableStateOf(false)
        private set
    var hasCurrentFrame: Boolean by mutableStateOf(false)
        private set
    val isBound: Boolean get() = identity != null

    /** 每次挂上纹理都是新输出会话：当前帧作废，只有换成另一块纹理才丢掉历史帧。 */
    fun bind(output: Any) {
        val replaced = identity !== output && detached !== output
        identity = output
        detached = null
        generation += 1L
        if (replaced) hasFrame = false
        beginSource()
    }

    /** 纹理随 View 消失但被保留：画面还在，输出与一次性重绘配额一起作废，重绑后才准重绘。 */
    fun detach(output: Any): Boolean {
        if (identity !== output) return false
        identity = null
        detached = output
        generation += 1L
        beginSource()
        return true
    }

    fun unbind(output: Any): Boolean {
        if (identity !== output && detached !== output) return false
        identity = null
        detached = null
        generation += 1L
        hasFrame = false
        beginSource()
        return true
    }

    fun beginSource() {
        rendererReady = false
        textureUpdated = false
        hasCurrentFrame = false
    }

    fun rendered() {
        if (identity == null) return
        rendererReady = true
        hasCurrentFrame = textureUpdated
    }

    /**
     * 纹理上来了新帧。
     *
     * 只要 [identity] 已经是这块输出，这一帧就必然来自**新**输出会话（重绑后纹理是新的，
     * 不可能夹带旧帧），因此它同时证明了渲染器已就绪——不能只依赖 `onRenderedFirstFrame`：
     * 那是带 mediaId 的一次性事件，surface 重绑（切页回来）时不会再发，
     * 依赖它就永远停在"正在等待画面"，而画面其实一直在出。
     */
    fun updated(output: Any): Boolean {
        if (identity !== output) return false
        hasFrame = true
        textureUpdated = true
        rendererReady = true
        hasCurrentFrame = true
        return true
    }

    fun acknowledgeSeek() {
        redrawGeneration = generation
    }

    /** 只对缺帧的新输出重绘一次；EOS 必须落在合法末帧范围，不能再次 seek 到时长末端。 */
    fun redrawPosition(
        positionMs: Long,
        durationMs: Long,
        ended: Boolean,
        lastFramePositionMs: Long? = null,
    ): Long? {
        val alreadyDrawn = hasCurrentFrame || redrawGeneration == generation
        if (identity == null || alreadyDrawn) return null
        redrawGeneration = generation
        val position = (if (ended) lastFramePositionMs ?: positionMs else positionMs).coerceAtLeast(0L)
        return if (ended && durationMs > 0L) position.coerceAtMost(durationMs - 1L) else position
    }
}

/** 只弱持有 View 身份；destroy 返回前不得绑定，排队期间只认仍存活的最新请求。 */
internal class Media3OutputHandoff<T : Any> {
    private var targetReference: WeakReference<T>? = null
    private var suspendedReference: WeakReference<T>? = null
    private var presentedReference: WeakReference<T>? = null
    var target: T?
        get() = targetReference?.get()
        private set(value) { targetReference = value?.let(::WeakReference) }
    var suspended: T?
        get() = suspendedReference?.get()
        private set(value) { suspendedReference = value?.let(::WeakReference) }
    private var revision = 0L
    private var detaching: Long? = null
    private var presented: T?
        get() = presentedReference?.get()
        set(value) { presentedReference = value?.let(::WeakReference) }

    fun request(view: T?) {
        target = view
        suspended = null
    }

    fun clear(view: T): Boolean {
        if (target !== view && suspended !== view) return false
        if (target === view) target = null
        if (suspended === view) suspended = null
        return true
    }

    fun present(view: T) {
        presented = view
    }

    /** 待交接 View 暂离时沿用旧输出；保留重挂请求，但显式新请求可将其作废。 */
    fun suspendPending(view: T, bound: T?): Boolean {
        if (presented === view || target !== view) return false
        suspended = view
        target = bound
        return true
    }

    fun resumePending(view: T): Boolean {
        if (suspended !== view) return false
        request(view)
        return true
    }

    fun next(bound: T?, attached: Boolean): T? =
        target?.takeIf { detaching == null && bound == null && attached }

    fun detach(): Long {
        revision += 1L
        detaching = revision
        return revision
    }

    fun completeDetach(ticket: Long): Boolean {
        if (detaching != ticket) return false
        detaching = null
        return true
    }

    fun reset() {
        target = null
        presented = null
        suspended = null
        // 取消目标而非物理销毁边界：可复用 clear 后的新请求也必须等旧 destroy 返回。
    }
}
