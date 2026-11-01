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

/**
 * VM 与"此刻那一台共享播放器"之间的插槽：只由组合层写入，VM 自己不创建、不释放。
 *
 * 短剧 feed 整个页面共用一个 [VerticalPlaybackOwner]（见 `ShortPlayFeedPlayback`），
 * 所以 VM 不能再按需建引擎——那正是"每部剧一个播放器、解码器只增不减"的来源。
 * 写入方离场即清空：交接层读不到引擎时退回状态里已有的进度，不会读到已释放的播放器。
 */
internal class VerticalPlaybackStore {

    private var current: VerticalPlaybackOwner? = null

    fun attach(owner: VerticalPlaybackOwner) {
        current = owner
    }

    fun detach(owner: VerticalPlaybackOwner) {
        if (current === owner) current = null
    }

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
}
