package com.starveil.feature.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 媒体身份包含集下标：相邻集即使 URL 相同，也必须重新从头准备。 */
internal data class VerticalMediaSource(
    val url: String,
    val headers: Map<String, String>,
    val audioUrl: String?,
    val episodeIndex: Int,
    val revision: Long,
)

internal fun VerticalPlayUiState.mediaSource(): VerticalMediaSource? = playingUrl?.let {
    VerticalMediaSource(it, headers, audioUrl, currentIndex, playbackRevision)
}

/** 可用 fake 验证 prepare → seek → play 的顺序，不在 JVM 测试中创建 Android 播放器。 */
internal interface VerticalPlaybackOutput {
    fun prepare(source: VerticalMediaSource)
    fun seekTo(positionMs: Long)
    fun play()
    fun pause()
    fun recover(positionMs: Long)
    fun setSpeed(speed: Float)
}

internal class VerticalPlaybackSession(
    private val output: VerticalPlaybackOutput,
    private val isOwner: (Any) -> Boolean = { true },
) {
    private var preparedSource: VerticalMediaSource? by mutableStateOf(null)
    private var appliedSeekRevision = -1L
    private var enabled = false

    /** 实际已准备媒体的页号；请求页可能已先改变，不能拿它给旧纹理重新归属。 */
    val preparedMediaPage: Int? get() = preparedSource?.episodeIndex

    fun update(state: VerticalPlayUiState, active: Boolean, speed: Float, token: Any? = null) {
        if (token != null && !isOwner(token)) return
        enabled = active
        val source = state.mediaSource()
        val canUseSource = active && !state.isBlocked && state.errorMessage == null
        // 权限同步/后台/滑动只暂停；不能 prepare 未获准的集，也不能丢掉现有 prepare 身份。
        if (!canUseSource || source == null) {
            output.pause()
            return
        }
        updatePreparedSource(state, source)
        output.setSpeed(speed)
        if (canPlay(state)) {
            output.play()
        } else {
            output.pause()
        }
    }

    private fun updatePreparedSource(state: VerticalPlayUiState, source: VerticalMediaSource) {
        if (source != preparedSource) {
            // prepare 沿用引擎的播放意图；必须先暂停，seek 完成后才允许发声。
            output.pause()
            preparedSource = source
            output.prepare(source)
            output.seekTo(state.positionMs.coerceAtLeast(0L))
            appliedSeekRevision = state.seekRevision
        } else if (appliedSeekRevision != state.seekRevision) {
            output.seekTo(state.positionMs.coerceAtLeast(0L))
            appliedSeekRevision = state.seekRevision
        }
    }

    fun isPrepared(state: VerticalPlayUiState): Boolean =
        preparedSource != null && preparedSource == state.mediaSource()

    fun isCurrent(state: VerticalPlayUiState): Boolean =
        isPrepared(state) && appliedSeekRevision == state.seekRevision

    fun canObserve(state: VerticalPlayUiState): Boolean = enabled && isCurrent(state)

    fun canPlay(state: VerticalPlayUiState): Boolean =
        canObserve(state) && state.isPlaying && !state.isBlocked && state.errorMessage == null

    /** 同步停播；只在可见 → 隐藏时捕获进度，重复 inactive 不回写尚未完成的 seek。 */
    fun suspendPlayback(token: Any? = null): Boolean {
        val ownsPlayback = token == null || isOwner(token)
        val canSuspend = ownsPlayback && enabled
        if (canSuspend) {
            enabled = false
            output.pause()
        }
        return canSuspend
    }
}

internal class VerticalMedia3Output(private val engine: Media3Player) : VerticalPlaybackOutput {
    override fun prepare(source: VerticalMediaSource) =
        engine.prepare(source.url, source.headers, source.audioUrl)

    override fun seekTo(positionMs: Long) = engine.seekTo(positionMs)
    override fun play() = engine.play()
    override fun pause() = engine.pause()
    override fun recover(positionMs: Long) = engine.recoverIfNeeded(positionMs)
    override fun setSpeed(speed: Float) = engine.setSpeed(speed)

}
