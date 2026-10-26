package com.starveil.app.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import com.starveil.core.designsystem.token.Motion
import com.starveil.feature.player.VerticalPlayViewModel
import com.starveil.feature.shortplay.SHORT_PLAY_ROUTE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class ShortPlayPhase { Preview, Opening, Series, Closing }

internal data class ShortPlayPresentationSession(
    val vodId: Int? = null,
    val sessionId: Long = 0L,
    val phase: ShortPlayPhase = ShortPlayPhase.Preview,
) {
    val expanded: Boolean get() = phase != ShortPlayPhase.Preview
    val transitioning: Boolean get() = phase == ShortPlayPhase.Opening || phase == ShortPlayPhase.Closing
}

/** 只活在 feed entry 的 ViewModelStore；不保存旧 entry，也不将播放展示态写进 SavedState。 */
internal class ShortPlayPresentationStore : ViewModel() {
    private val mutableState = MutableStateFlow(ShortPlayPresentationSession())
    val state = mutableState.asStateFlow()
    val expansion = Animatable(0f)
    private var player: VerticalPlayViewModel? = null
    private var releasePin: (() -> Unit)? = null

    /** 顺序是契约：pin 防止在途刷新拆掉 Video，begin 成功后才对 UI 发布 Opening。 */
    fun open(vodId: Int, episode: Int, viewModel: VerticalPlayViewModel, pinPlayback: (Int?) -> Unit): Boolean {
        val current = state.value
        if (current.expanded) return false
        pinPlayback(vodId)
        val opened = viewModel.beginInlineSeries(episode)
        if (opened) {
            player = viewModel
            releasePin = { pinPlayback(null) }
            mutableState.value = ShortPlayPresentationSession(vodId, current.sessionId + 1L, ShortPlayPhase.Opening)
        } else {
            pinPlayback(null)
        }
        return opened
    }

    /** Opening 可直接反转；Closing 继续消费返回，不把它传给 NavHost。 */
    fun close() {
        val current = state.value
        if (!current.expanded || current.phase == ShortPlayPhase.Closing) return
        mutableState.value = current.copy(phase = ShortPlayPhase.Closing)
    }

    /** 只有壳层调用，AnimationFloat 是 dock/header/player 共用的唯一时钟。 */
    suspend fun animate(session: ShortPlayPresentationSession) {
        if (!session.transitioning || state.value != session) return
        val target = if (session.phase == ShortPlayPhase.Closing) 0f else 1f
        expansion.animateTo(target, tween(durationMillis = Motion.LongMs, easing = Motion.standard))
        if (state.value != session) return
        if (session.phase == ShortPlayPhase.Opening) {
            mutableState.value = session.copy(phase = ShortPlayPhase.Series)
        } else {
            player?.endInlineSeries()
            player = null
            releasePin?.invoke()
            releasePin = null
            mutableState.value = ShortPlayPresentationSession(sessionId = session.sessionId)
        }
    }
}

internal fun NavBackStackEntry.shortPlayPresentationStore(): ShortPlayPresentationStore =
    ViewModelProvider(this)[ShortPlayPresentationStore::class.java]

/** 回调只随当前组合持有 origin；store 自身绝不强持 NavBackStackEntry。 */
internal class ShortPlayInlineMode(
    val store: ShortPlayPresentationStore,
    val pinPlayback: (Int?) -> Unit,
    val isCurrent: () -> Boolean,
)

internal data class ShortPlayPresentationFrame(
    val originId: String? = null,
    val store: ShortPlayPresentationStore? = null,
    val expansion: Float = 0f,
)

internal val LocalShortPlayPresentation = compositionLocalOf { ShortPlayPresentationFrame() }

/** 仅投影当前栈顶；去付费/跨剧路由不把 origin 的 dock 隐藏状态带过去。 */
@Composable
internal fun rememberShortPlayPresentation(entry: NavBackStackEntry?): ShortPlayPresentationFrame {
    val store = remember(entry) {
        entry?.takeIf { it.destination.route == SHORT_PLAY_ROUTE }?.shortPlayPresentationStore()
    } ?: return ShortPlayPresentationFrame()
    val session by store.state.collectAsStateWithLifecycle()
    LaunchedEffect(store, session) { store.animate(session) }
    return ShortPlayPresentationFrame(entry?.id, store, store.expansion.value)
}
