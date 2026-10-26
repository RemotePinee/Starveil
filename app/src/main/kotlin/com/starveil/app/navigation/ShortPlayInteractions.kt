package com.starveil.app.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.starveil.app.AppContainer
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.designsystem.LocalLoginPrompt
import com.starveil.core.designsystem.LoginPrompt
import com.starveil.core.designsystem.LoginReason
import com.starveil.core.model.Session
import com.starveil.core.model.ShareLink
import com.starveil.feature.player.VERTICAL_ACTION_FAVORITE
import com.starveil.feature.player.VERTICAL_ACTION_SHARE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class ShortPlayInteractions(
    val onAction: (String) -> Unit,
    val isFavorite: Boolean = false,
    val pendingAction: String? = null,
)

/** 换剧或换会话时重建状态并取消请求；标题更新不重置已确认的收藏。 */
@Composable
internal fun rememberShortPlayInteractions(
    container: AppContainer,
    vodId: Int,
    title: String,
    active: Boolean = true,
): ShortPlayInteractions {
    val session by container.session.collectAsState()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val currentTitle by rememberUpdatedState(title)
    // 宿主对象（Context / HapticFeedback / LoginPrompt）随重组重建，不能让它们进 remember 的 key：
    // 一旦 actions 被重建，已确认的收藏就丢了，接着又发起一次后台查询。
    // rememberUpdatedState 只换引用，状态持有者本身稳定。
    val feedback = rememberUpdatedState(
        ShortPlayFeedback(LocalContext.current, LocalHapticFeedback.current, LocalLoginPrompt.current),
    )
    return key(container, vodId, session) {
        val scope = rememberCoroutineScope()
        // key 块已按 (container, vodId, session) 隔离：换剧/换会话才重建，标题与重组不重建。
        val actions = remember {
            ShortPlayActions(container, ActionIdentity(vodId, session), scope, feedback)
        }
        LaunchedEffect(actions, active, lifecycleState) {
            actions.syncFavorite(active && lifecycleState == Lifecycle.State.RESUMED)
        }
        ShortPlayInteractions(
            onAction = { label -> actions.onAction(label, currentTitle) },
            isFavorite = actions.isFavorite,
            pendingAction = actions.pendingAction,
        )
    }
}

private data class ShortPlayFeedback(
    val context: Context,
    val haptic: HapticFeedback,
    val loginPrompt: LoginPrompt,
) {
    fun confirm(message: String) {
        context.shortPlayToast(message)
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    fun reject(error: AppError) {
        context.shortPlayToast(error.toShortPlayMessage())
        haptic.performHapticFeedback(HapticFeedbackType.Reject)
    }
}

private data class ActionIdentity(val vodId: Int, val session: Session?)

private class ShortPlayActions(
    private val container: AppContainer,
    private val identity: ActionIdentity,
    private val scope: CoroutineScope,
    /** 只读最新的一份反馈宿主；它不参与状态重建，见 [rememberShortPlayInteractions]。 */
    private val feedbackState: State<ShortPlayFeedback>,
) {
    private val vodId get() = identity.vodId
    private val session get() = identity.session
    private val feedback get() = feedbackState.value

    var isFavorite by mutableStateOf(
        session?.isLoggedIn == true &&
            (container.favoriteRepository.cachedFavoriteStatus(vodId)
                ?: container.favoriteRepository.cachedFavorites()?.any { it.vodId == vodId }) == true,
    )
        private set
    var pendingAction by mutableStateOf<String?>(null)
        private set

    private var favoriteConfirmed = false
    private var wasResumed = false
    private var writeGeneration = 0
    private var syncJob: Job? = null

    /** 后台返回时先应用成功写入的本地状态，再静默向服务端核对。 */
    fun syncFavorite(resumed: Boolean) {
        if (!resumed) {
            wasResumed = false
        } else if (!wasResumed) {
            wasResumed = true
            favoriteConfirmed = false
            container.favoriteRepository.cachedFavoriteStatus(vodId)?.let { cached ->
                isFavorite = cached
            }
            if (canSyncSilently()) {
                val generation = writeGeneration
                syncJob = scope.launch { applySilentSync(generation) }
            }
        }
    }

    /** 静默同步的前置条件：已登录、同一会话、没查过、也没有用户写操作在跑。 */
    private fun canSyncSilently(): Boolean {
        val signedIn = session?.isLoggedIn == true && scope.isActive
        val idle = !favoriteConfirmed && pendingAction == null && syncJob?.isActive != true
        return signedIn && idle && isCurrentSession()
    }

    private suspend fun applySilentSync(generation: Int) {
        val favorited = when (val result = request { container.favoriteRepository.contains(vodId) }) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> return
        }
        if (generation != writeGeneration || pendingAction != null) return
        isFavorite = favorited
        favoriteConfirmed = true
    }

    /** 问一次服务端到底收没收藏；失败时保留上次确认的值，不把状态改成"未收藏"。 */
    private suspend fun confirmFavorite(): Boolean =
        when (val result = request { container.favoriteRepository.contains(vodId) }) {
            is AppResult.Success -> {
                isFavorite = result.value
                favoriteConfirmed = true
                true
            }
            is AppResult.Failure -> {
                feedback.reject(result.error)
                false
            }
        }

    fun onAction(label: String, title: String) {
        if (!scope.isActive || !isCurrentSession()) return
        if (pendingAction != null) {
            feedback.context.shortPlayToast("${pendingAction}处理中，请稍候")
            return
        }
        when (label) {
            VERTICAL_ACTION_FAVORITE -> toggleFavorite()
            VERTICAL_ACTION_SHARE -> share(title.trim())
            // 评论不在这里分流：应用壳在 VerticalPlayEntry 就地打开评论抽屉，不导航、不打断播放。
            else -> Unit
        }
    }

    private fun toggleFavorite() {
        if (session?.isLoggedIn != true) {
            feedback.loginPrompt.show(LoginReason.FAVORITE)
            return
        }
        // 用户接手：作废还在飞的静默查询，免得它的结果晚到把这次写操作盖回去。
        syncJob?.cancel()
        writeGeneration += 1
        launchAction(VERTICAL_ACTION_FAVORITE) {
            // 状态未知（没有快照、上次查询失败）时先确认再决定加减，不凭猜测写。
            if (!favoriteConfirmed && !confirmFavorite()) return@launchAction
            val shouldFavorite = !isFavorite
            when (val result = request { writeFavorite(shouldFavorite) }) {
                is AppResult.Success -> {
                    isFavorite = shouldFavorite
                    favoriteConfirmed = true
                    feedback.confirm(if (shouldFavorite) "已收藏" else "已取消收藏")
                }
                is AppResult.Failure -> {
                    // 写没落地 → 状态回到未知，下次点击先确认，不沿用猜测。
                    favoriteConfirmed = false
                    feedback.reject(result.error)
                }
            }
        }
    }

    private suspend fun writeFavorite(shouldFavorite: Boolean): AppResult<Unit> =
        if (shouldFavorite) container.favoriteRepository.add(vodId)
        else container.favoriteRepository.remove(listOf(vodId))

    private fun share(title: String) {
        if (title.isBlank()) {
            feedback.context.shortPlayToast("剧集信息加载中")
            return
        }
        launchAction(VERTICAL_ACTION_SHARE) {
            feedback.context.shortPlayToast("正在获取分享信息…")
            when (val result = request { container.pointsRepository.shareLink() }) {
                is AppResult.Success -> {
                    val opened = feedback.context.openShortPlayShare(title, result.value)
                    if (opened is AppResult.Failure) feedback.reject(opened.error)
                }
                is AppResult.Failure -> feedback.reject(result.error)
            }
        }
    }

    /** 同一把锁串行化动作，失败和取消均释放；不把打开分享面板当成分享成功。 */
    private fun launchAction(label: String, action: suspend () -> Unit) {
        pendingAction = label
        scope.launch {
            try {
                action()
            } finally {
                pendingAction = null
            }
        }
    }

    private fun isCurrentSession(): Boolean =
        container.session.value == session && container.sessionSnapshot() == session

    private fun ensureCurrentSession() {
        if (!isCurrentSession()) throw CancellationException("短剧操作会话已变化")
    }

    private suspend fun <T> request(call: suspend () -> AppResult<T>): AppResult<T> {
        currentCoroutineContext().ensureActive()
        ensureCurrentSession()
        val result = runCatching { call() }.getOrElse { error ->
            if (error is CancellationException) throw error
            AppResult.Failure(AppError.Unknown(error))
        }
        currentCoroutineContext().ensureActive()
        ensureCurrentSession()
        return result
    }
}

/** 正文仅包含真实剧名与后端配置的有效分享链接，不使用全局推广文案。 */
private fun Context.openShortPlayShare(title: String, link: ShareLink): AppResult<Unit> {
    val url = link.url.trim()
    if (!isShortPlayShareUrl(url)) {
        return AppResult.Failure(AppError.Business(0, "分享链接未配置或无效，请稍后重试"))
    }
    val body = "《$title》\n$url"
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    val chooser = Intent.createChooser(send, "分享《$title》").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        startActivity(chooser)
        AppResult.Success(Unit)
    } catch (_: ActivityNotFoundException) {
        AppResult.Failure(AppError.Business(0, "没有可用的分享应用"))
    } catch (_: SecurityException) {
        AppResult.Failure(AppError.Business(0, "无法打开分享面板，请稍后重试"))
    }
}

private fun isShortPlayShareUrl(url: String): Boolean {
    if (url.isBlank() || url.any { it.isWhitespace() }) return false
    val uri = Uri.parse(url)
    val validScheme = uri.scheme.equals("https", ignoreCase = true) ||
        uri.scheme.equals("http", ignoreCase = true)
    return validScheme && uri.isHierarchical && !uri.host.isNullOrBlank()
}

private fun AppError.toShortPlayMessage(): String = when (this) {
    is AppError.Business -> message.ifBlank { "操作失败，请重试" }
    is AppError.Network -> "网络异常，请稍后重试"
    is AppError.Http -> "服务异常（$code）"
    is AppError.Serialization -> "数据解析失败"
    is AppError.Unknown -> "操作失败，请稍后重试"
}

/** 短剧这几处提示都只差一个 Toast；交互层与评论抽屉共用同一个出口。 */
internal fun Context.shortPlayToast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
