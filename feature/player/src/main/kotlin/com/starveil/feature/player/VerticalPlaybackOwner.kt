package com.starveil.feature.player

import android.content.Context

/** 每个剧集 ViewModel 独占，不跨剧共享播放器或画面，也不保存截图。 */
internal class VerticalPlaybackOwner(context: Context, isOwner: (Any) -> Boolean) {
    val engine = Media3Player(context.applicationContext)
    val session = VerticalPlaybackSession(VerticalMedia3Output(engine), isOwner)
    val controller = VerticalPlaybackController()

    fun release() {
        session.suspendPlayback()
        engine.release()
    }
}

/** 同一个 preview token 再次挂载也会换 controller；延迟到达的旧组合清理没有控制权。 */
internal class VerticalPlaybackController {
    private var current: Any? = null

    fun claim(token: Any) { current = token }
    fun owns(token: Any): Boolean = current === token
    fun clear(token: Any) {
        if (owns(token)) current = null
    }
}

/** 导航暂离保留解码器与 prepare 身份；推荐页移出组合时由调用方主动回收，避免无限累积。 */
internal class VerticalPlaybackStore(private val isOwner: (Any) -> Boolean) {
    private var current: VerticalPlaybackOwner? = null

    fun get(context: Context): VerticalPlaybackOwner =
        current ?: VerticalPlaybackOwner(context, isOwner).also { current = it }

    /** 原地切换只读取真实进度，不能暂停会话或改变 prepare/seek 身份。 */
    fun snapshot(state: VerticalPlayUiState): VerticalPlayUiState {
        val owner = current ?: return state
        return if (owner.session.isCurrent(state)) {
            state.copy(positionMs = owner.engine.currentPositionMs, durationMs = owner.engine.durationMs)
        } else {
            state
        }
    }

    fun capture(state: VerticalPlayUiState): VerticalPlayUiState {
        current?.session?.suspendPlayback()
        return snapshot(state)
    }

    fun release(owner: VerticalPlaybackOwner) {
        if (current !== owner) return
        current = null
        owner.release()
    }

    fun clear() {
        current?.let(::release)
    }
}
