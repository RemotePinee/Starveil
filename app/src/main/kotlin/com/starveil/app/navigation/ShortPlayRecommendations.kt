package com.starveil.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.starveil.app.AppContainer
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.common.map
import com.starveil.core.model.Session
import com.starveil.core.model.Vod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class ShortPlayRecommendations(
    val items: List<Vod> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val load: () -> Unit = {},
)

@Composable
internal fun rememberShortPlayRecommendations(
    container: AppContainer,
    vodId: Int,
    typeId: Int,
    enabled: Boolean,
): ShortPlayRecommendations {
    val session by container.session.collectAsState()
    val typeIds = container.startupRepository.cached()?.shortplayTypes.orEmpty()
        .map { it.id }.filter { it > 0 }.toSet()
    return key(container, vodId, typeId, enabled, session, typeIds) {
        val scope = rememberCoroutineScope()
        val loader = remember {
            ShortPlayRecommendationLoader(container, session, scope) {
                container.fetchShortPlayRecommendations(vodId, typeId, typeIds, session)
            }
        }
        loader.state.copy(load = { if (enabled) loader.load() })
    }
}

private class ShortPlayRecommendationLoader(
    private val container: AppContainer,
    private val session: Session?,
    private val scope: CoroutineScope,
    private val request: suspend () -> AppResult<List<Vod>>,
) {
    var state by mutableStateOf(ShortPlayRecommendations())
        private set

    fun load() {
        if (state.loading || !scope.isActive) return
        if (!container.isRecommendationSession(session)) return
        state = state.copy(loading = true, error = null)
        scope.launch {
            try {
                when (val result = recommendationRequest(container, session, request)) {
                    is AppResult.Success -> state = state.copy(items = result.value)
                    is AppResult.Failure -> state = state.copy(error = result.error.recommendationMessage())
                }
            } finally {
                state = state.copy(loading = false)
            }
        }
    }
}

private suspend fun AppContainer.fetchShortPlayRecommendations(
    vodId: Int,
    typeId: Int,
    typeIds: Set<Int>,
    session: Session?,
): AppResult<List<Vod>> {
    if (typeId !in typeIds) {
        return AppResult.Failure(AppError.Business(0, "当前分类不在后台短剧配置中，无法加载推荐"))
    }
    val configured = recommendationRequest(this, session) { vodRepository.guessByType(typeId) }
    val candidates = when (configured) {
        is AppResult.Failure -> configured
        is AppResult.Success -> if (configured.value.items.isNotEmpty()) {
            AppResult.Success(configured.value.items)
        } else {
            recommendationRequest(this, session) {
                vodRepository.list(type = typeId, page = 1, limit = 20)
            // 沿用分类自动切片；小分类不足一片时保留候选，避免把已有的其它短剧全部丢空。
            }.map { items -> items.drop(6).ifEmpty { items } }
        }
    }
    return candidates.map { items ->
        items.filter { it.id != vodId && it.typeId in typeIds }.distinctBy { it.id }
    }
}

private fun AppContainer.isRecommendationSession(session: Session?): Boolean =
    this.session.value == session && sessionSnapshot() == session

private fun ensureRecommendationSession(container: AppContainer, session: Session?) {
    if (!container.isRecommendationSession(session)) throw CancellationException("短剧推荐会话已变化")
}

private suspend fun <T> recommendationRequest(
    container: AppContainer,
    session: Session?,
    call: suspend () -> AppResult<T>,
): AppResult<T> {
    currentCoroutineContext().ensureActive()
    ensureRecommendationSession(container, session)
    val result = runCatching { call() }.getOrElse { error ->
        if (error is CancellationException) throw error
        AppResult.Failure(AppError.Unknown(error))
    }
    currentCoroutineContext().ensureActive()
    ensureRecommendationSession(container, session)
    return result
}

private fun AppError.recommendationMessage(): String = when (this) {
    is AppError.Business -> message.ifBlank { "推荐加载失败，请重试" }
    is AppError.Network -> "网络异常，请稍后重试"
    is AppError.Http -> "服务异常（$code），请稍后重试"
    is AppError.Serialization -> "推荐数据解析失败，请重试"
    is AppError.Unknown -> "推荐加载失败，请稍后重试"
}
