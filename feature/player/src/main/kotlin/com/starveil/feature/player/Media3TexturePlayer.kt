package com.starveil.feature.player

import android.graphics.SurfaceTexture
import android.os.Handler
import android.view.TextureView
import android.view.View
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.lang.ref.WeakReference

/** 官方 PlayerSurface 管 View；同 VM 暂离时局部保留已分离的真实纹理，永久释放时归还资源。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class Media3TexturePlayer(player: Player, private val frames: Media3FrameState) : ForwardingPlayer(player) {
    private var binding: Binding? = null
    private var retained: SurfaceTexture? = null
    private val handoff = Media3OutputHandoff<TextureView>()
    private val handler = Handler(player.applicationLooper)
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            unwatchTargets()
            if (view is TextureView) handoff.resumePending(view)
            watchTargets()
            bindRequested()
        }

        override fun onViewDetachedFromWindow(view: View) {
            // 从未创建纹理的 View 不会有 destroy 回调，也不能让 Binding 留住旧 Activity。
            if (binding?.view === view && binding?.texture == null) clearBinding()
            // 未绑定目标暂离不能抢走旧输出；同 View 重挂仍可恢复，显式新 set 则将其作废。
            val target = handoff.target ?: return
            if (target === view) {
                unwatchTargets()
                handoff.suspendPending(target, binding?.view)
                watchTargets()
            }
        }
    }

    override fun setVideoTextureView(textureView: TextureView?) {
        if (frames.isReleased) return
        unwatchTargets()
        handoff.request(textureView)
        watchTargets()
        bindRequested()
    }

    private fun unwatchTargets() {
        handoff.target?.removeOnAttachStateChangeListener(attachment)
        handoff.suspended?.removeOnAttachStateChangeListener(attachment)
    }

    private fun watchTargets() {
        handoff.target?.addOnAttachStateChangeListener(attachment)
        handoff.suspended?.addOnAttachStateChangeListener(attachment)
    }

    private fun bindRequested() {
        if (frames.isReleased) return
        // 无纹理的旧绑定不会收到 destroy，不能让它堵住最新目标。
        if (binding?.view !== handoff.target && binding?.texture == null) clearBinding()
        val target = handoff.target
        val previous = binding?.view
        val next = handoff.next(previous, target?.isAttachedToWindow == true) ?: return
        // 换了 View（切页回来）：ExoPlayer 不会因为 View 变了就丢掉旧 Surface，
        // 它会继续往那块已经没人显示的老纹理写画面 —— 有声音、没画面就是这么来的。
        // 显式清掉再设，才会按新纹理重建渲染目标，等价于首次进入的时序。
        if (previous != null && previous !== next) super.clearVideoTextureView(null)
        retained?.let { next.setSurfaceTexture(it) }
        retained = null
        super.setVideoTextureView(next)
        bind(next)
    }

    private fun bind(textureView: TextureView) {
        val current = textureView.surfaceTextureListener ?: return
        // View 被复用时，监听器可能还是我们上一轮装的 Binding。再包一层就成了
        // Binding → Binding：外层的 available() 只喂给内层，ExoPlayer 永远收不到
        // onSurfaceTextureAvailable，也就不会建 Surface —— 有声音、没画面。
        // 剥掉旧包裹，始终拿到 ExoPlayer 自己的 listener。
        val delegate = if (current is Binding) current.delegate else current
        val next = Binding(textureView, delegate)
        binding = next
        textureView.surfaceTextureListener = next
        handoff.present(textureView)
        // 已经有纹理时（保留纹理重挂）TextureView 不会再发 onSurfaceTextureAvailable，
        // 但 ExoPlayer 的 setVideoTextureView 按 isAvailable() 自己建了 Surface，输出不缺；
        // 只补本地的输出会话，不能再去通知 delegate，否则会重复换输出面。
        textureView.surfaceTexture?.let(next::available)
    }


    override fun clearVideoTextureView(textureView: TextureView?) {
        // 旧组合的延迟 clear 只能取消自己的请求，不能取消新目标或抢走旧纹理的 destroy。
        if (textureView == null) return
        unwatchTargets()
        handoff.clear(textureView)
        watchTargets()
        if (binding?.view === textureView && binding?.texture == null) clearBinding()
        // 真正解绑仍等纹理销毁；此处提前拆 listener 会丢掉保留纹理的机会。
    }

    fun releaseBinding() {
        unwatchTargets()
        handoff.reset()
        clearBinding()
        retained?.let {
            frames.unbindOutput(it)
            it.release()
        }
        retained = null
    }

    private fun clearBinding() {
        val previous = binding ?: return
        restoreListener()
        super.clearVideoTextureView(previous.view)
    }

    private fun restoreListener() {
        val previous = binding ?: return
        binding = null
        previous.texture?.let(frames::unbindOutput)
        // ExoPlayer 检查 listener 身份；清理前还原，不能留下包裹 listener 或触发错误身份清理。
        if (previous.view.surfaceTextureListener === previous) {
            previous.view.surfaceTextureListener = previous.delegate
        }
    }

    private fun postHandoff(ticket: Long) {
        // 不能在 Binding 内创建闭包：它可能捕获 Binding -> View -> Activity。
        // 队列只保留票据和弱 owner，release/Activity 重建后不会延长旧输出寿命。
        val owner = WeakReference(this)
        handler.post { owner.get()?.completeHandoff(ticket) }
    }

    private fun completeHandoff(ticket: Long) {
        if (handoff.completeDetach(ticket)) bindRequested()
    }

    private inner class Binding(
        val view: TextureView,
        val delegate: TextureView.SurfaceTextureListener,
    ) : TextureView.SurfaceTextureListener {
        var texture: SurfaceTexture? = null
            private set

        fun available(surface: SurfaceTexture) {
            if (binding !== this || texture === surface) return
            texture = surface
            frames.bindOutput(surface)
        }

        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            if (binding !== this) return
            available(surface)
            delegate.onSurfaceTextureAvailable(surface, width, height)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            if (binding === this && texture === surface) {
                delegate.onSurfaceTextureSizeChanged(surface, width, height)
            }
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            if (binding !== this || texture !== surface) return true
            val ticket = handoff.detach()
            // Media3 只清理自己的 Surface；返回 false 后纹理由本实例保留，不能交给 View 销毁。
            // destroy 不保证清理 TextureView 的 mUpdateListener；保留纹理不能保留旧 View。
            // 新 View 在建层/setSurfaceTexture 时会自行安装其帧监听。
            surface.setOnFrameAvailableListener(null)
            retained = surface
            // 先作废当前输出：暂离返回时 restoreVideoFrameIfNeeded 才有一次合法的 seek 重绘，
            // 否则 hasCurrentFrame/generation 仍是旧的，新 Surface 永远等不到首帧。
            frames.detachOutput(surface)
            texture = null
            delegate.onSurfaceTextureDestroyed(surface)
            clearBinding()
            // TextureView 在回调返回之后才销毁 native window 并将 mSurface 置空。
            // 用播放器主线程队列（不是 detached View.post）跨过该边界，再读取最新请求。
            postHandoff(ticket)
            return false
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            if (binding !== this || texture !== surface) return
            frames.output.updated(surface)
            delegate.onSurfaceTextureUpdated(surface)
        }
    }
}
