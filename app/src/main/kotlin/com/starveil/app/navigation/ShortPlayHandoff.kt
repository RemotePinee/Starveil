package com.starveil.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.starveil.feature.player.VerticalPlayViewModel
import com.starveil.feature.shortplay.SHORT_PLAY_ROUTE

internal fun shortPlayViewModelKey(vodId: Int): String = "shortplay-vod-$vodId"

/** 只存于真正 origin feed entry 的 ViewModelStore，不按路由字符串寻找任意历史同剧。 */
internal data class ShortPlayHandoff(
    val origin: NavBackStackEntry,
    val vodId: Int,
    val viewModel: VerticalPlayViewModel,
    val token: Any = Any(),
)

internal class ShortPlayHandoffStore : ViewModel() {
    var pending: ShortPlayHandoff? = null
        private set

    fun begin(handoff: ShortPlayHandoff, episodeNumber: Int): Boolean {
        if (pending != null || !handoff.viewModel.beginPreviewHandoff(handoff.token, episodeNumber)) return false
        pending = handoff
        return true
    }

    fun find(origin: NavBackStackEntry, vodId: Int): ShortPlayHandoff? {
        val handoff = pending ?: return null
        val sameOrigin = handoff.origin.id == origin.id && handoff.vodId == vodId
        val ownsPlayback = handoff.viewModel.ownsPlayback(handoff.token) && handoff.viewModel.hasPreviewHandoff
        return if (sameOrigin && ownsPlayback) {
            handoff.copy(origin = origin).also { pending = it }
        } else {
            null
        }
    }

    fun finish(handoff: ShortPlayHandoff) {
        if (pending?.token !== handoff.token) return
        handoff.viewModel.endPreviewHandoff(handoff.token)
        pending = null
    }

    override fun onCleared() {
        pending?.let(::finish)
        super.onCleared()
    }
}

private fun NavBackStackEntry.handoffStore(): ShortPlayHandoffStore =
    ViewModelProvider(this)[ShortPlayHandoffStore::class.java]

internal fun beginShortPlayHandoff(
    origin: NavBackStackEntry,
    vodId: Int,
    viewModel: VerticalPlayViewModel,
    episodeNumber: Int,
): Boolean = origin.handoffStore().begin(ShortPlayHandoff(origin, vodId, viewModel), episodeNumber)

internal fun findShortPlayHandoff(previous: NavBackStackEntry?, vodId: Int): ShortPlayHandoff? {
    val origin = previous?.takeIf {
        it.destination.route == SHORT_PLAY_ROUTE && it.lifecycle.currentState != Lifecycle.State.DESTROYED
    } ?: return null
    return origin.handoffStore().find(origin, vodId)
}

/** 交接寿命跟随全集 entry 的 ViewModelStore；暂离组合与 Activity 重建都不能提前归还。 */
internal class ShortPlayHandoffLease(
    val handoff: ShortPlayHandoff?,
    private val store: ShortPlayHandoffStore?,
) : ViewModel() {
    fun finish() {
        handoff?.let { store?.finish(it) }
    }

    override fun onCleared() {
        finish()
        super.onCleared()
    }
}

internal fun shortPlayHandoffLease(
    entry: NavBackStackEntry,
    previous: NavBackStackEntry?,
    vodId: Int,
): ShortPlayHandoffLease = ViewModelProvider(
    entry,
    viewModelFactory {
        initializer {
            val handoff = findShortPlayHandoff(previous, vodId)
            ShortPlayHandoffLease(handoff, handoff?.origin?.handoffStore())
        }
    },
)[ShortPlayHandoffLease::class.java]

@Composable
internal fun ObserveShortPlayPreview(
    origin: NavBackStackEntry,
    viewModel: VerticalPlayViewModel,
    nav: NavHostController,
) {
    DisposableEffect(origin, viewModel, nav) {
        val store = origin.handoffStore()
        val observer = LifecycleEventObserver { _, event ->
            // addObserver 会补发历史 ON_RESUME；只有当前栈顶真是 origin 才是返回。
            if (event == Lifecycle.Event.ON_RESUME && nav.currentBackStackEntry?.id == origin.id) {
                store.pending?.takeIf { it.viewModel === viewModel }?.let(store::finish)
            }
        }
        origin.lifecycle.addObserver(observer)
        onDispose { origin.lifecycle.removeObserver(observer) }
    }
}
