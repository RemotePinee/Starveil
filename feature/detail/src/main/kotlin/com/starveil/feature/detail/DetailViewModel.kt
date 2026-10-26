package com.starveil.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.data.AuthRepository
import com.starveil.core.data.CommentRepository
import com.starveil.core.data.FavoriteRepository
import com.starveil.core.data.VodRepository
import com.starveil.core.model.Vod
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 详情页状态持有者。
 *
 * 与的三处差异：
 * 1. 旧页用 `limit=100` 拉一次收藏列表判断是否已收藏，失败分支是空的
 * 判定失败降级成"未收藏"，
 * 点收藏时会重新查询再决定加还是减，不会因为一次抖动把已收藏显示成未收藏。
 * 2. 旧页未登录点收藏直接跳 `LoginActivity`；
 * 只抛一次性事件，跳转交给 :app 的导航，feature 不持有 NavController。
 * 3. 旧页收藏/取消成功后靠重设图标表达状态；
 * 状态在 VM 内更新，UI 只读。
 */
/** 仓库依赖包：构造函数参数压到 2 个，避免 detekt LongParameterList。 */
data class DetailDeps(
    val vodRepository: VodRepository,
    val favoriteRepository: FavoriteRepository,
    val authRepository: AuthRepository,
    val commentRepository: CommentRepository,
)

class DetailViewModel(
    private val deps: DetailDeps,
    private val vodId: Int,
) : ViewModel() {

    private val _state = MutableStateFlow<DetailUiState>(
        // 上次看过同一部片：直接铺内容，网络回来原地刷新，不再进来先转圈
        deps.vodRepository.cachedDetail(vodId)
            ?.let { vod -> DetailUiState.Content(vod = vod) }
            ?: DetailUiState.Loading,
    )
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private val _events = Channel<DetailEvent>(Channel.BUFFERED)
    val events: Flow<DetailEvent> = _events.receiveAsFlow()

    private val commentController = DetailCommentController(
        context = CommentContext(
            repository = deps.commentRepository,
            authRepository = deps.authRepository,
            vodId = vodId,
        ),
        scope = viewModelScope,
        callbacks = CommentCallbacks(
            notify = ::notify,
            // 详情页没有登录弹层：未登录发布与从前一样只提示一句。
            onSignInRequired = { notify(SIGN_IN_REQUIRED) },
            onPosted = { _events.trySend(DetailEvent.CommentPosted) },
        ),
    )

    /** 评论区状态。与 [state] 分开，评论拉取失败不影响详情主体。 */
    val comments: StateFlow<CommentSectionState> = commentController.state

    init {
        load(keepContent = false)
    }

    /** 错误态里的"重试"：整页回到 Loading 再拉一次。 */
    fun retry() = load(keepContent = false)

    /** 重新进页面 / 手动刷新：有内容就保留，避免整页闪白。 */
    fun refresh() = load(keepContent = true)

    /** 评论区「重试」或重新进页面：拉第一页。 */
    fun loadComments() = commentController.loadFirstPage()

    /** 展开后底部的「加载更多」：追加下一页。 */
    fun loadMoreComments() = commentController.loadMore()

    /** 「查看全部 N 条」/「收起」：同页展开，不跳路由。 */
    fun toggleCommentsExpanded() = commentController.toggleExpanded()

    /** 发布评论：未登录只提示、不发请求；成功后刷新第一页。 */
    fun submitComment(content: String) = commentController.submit(content)

    /** 切播放源：旧页切 tab 后选集整条换成新源的列表。 */
    fun selectSource(index: Int) = updateContent { it.withSource(index) }

    /** 选集超过 [EPISODE_COLLAPSE_LIMIT] 集时的「展开全部 / 收起」。 */
    fun toggleEpisodeGrid() = updateContent { it.copy(isEpisodeGridExpanded = !it.isEpisodeGridExpanded) }

    /** 选集：只改高亮下标，真正起播由页面回调 `onPlay` 决定。 */
    fun selectEpisode(index: Int) = updateContent { it.withEpisode(index) }

    fun toggleBlurb() = updateContent { it.copy(isBlurbExpanded = !it.isBlurbExpanded) }

    /**
     * 收藏 / 取消收藏。
     *
     * 口径照搬旧页：先查第 1 页收藏列表里有没有本片，再决定调 add 还是 remove
     * （旧 + `:669-690`）。后端没有"切换"语义，
     * 所以不能凭本地状态直接反着调。
     */
    fun toggleFavorite() {
        val content = _state.value as? DetailUiState.Content
        if (content == null || content.favorite.isPending) return
        val previous = content.favorite
        updateContent { it.copy(favorite = DetailFavoriteState.Pending) }
        viewModelScope.launch { requestToggle(previous) }
    }

    private fun load(keepContent: Boolean) {
        val previous = _state.value as? DetailUiState.Content
        if (keepContent && previous != null) {
            updateContent { it.copy(isRefreshing = true) }
        } else if (_state.value !is DetailUiState.Content) {
            // 快照已经铺出内容时不要再打回 Loading，否则刚显示出来的详情又变成转圈
            _state.value = DetailUiState.Loading
        }
        viewModelScope.launch { loadDetail(previous) }
    }

    private suspend fun loadDetail(previous: DetailUiState.Content?) {
        when (val result = deps.vodRepository.detail(vodId)) {
            is AppResult.Success -> applyDetail(result.value, previous)
            is AppResult.Failure -> applyDetailFailure(result.error, previous)
        }
    }

    private suspend fun applyDetail(vod: Vod, previous: DetailUiState.Content?) {
        if (vod.playSources.none { it.episodes.isNotEmpty() }) {
            _state.value = DetailUiState.Empty(EMPTY_SOURCES)
            return
        }
        val sourceIndex = resolveSourceIndex(vod, previous?.sourceIndex ?: 0)
        val episodes = vod.playSources[sourceIndex].episodes
        _state.value = DetailUiState.Content(
            vod = vod,
            sourceIndex = sourceIndex,
            episodeIndex = previous?.episodeIndex?.takeIf { it in episodes.indices } ?: 0,
            // 收藏状态是一次额外的列表请求，不该挡在首屏渲染前面；先按未收藏画，回来再改
            favorite = previous?.favorite ?: DetailFavoriteState.NotFavorited,
            isBlurbExpanded = previous?.isBlurbExpanded ?: false,
            recommendationsLoading = vod.typeId > 0,
        )
        commentController.loadFirstPage()
        // 收藏与推荐都是次要内容：并行补，谁先回来谁先上屏
        viewModelScope.launch {
            val favorite = loadFavoriteState()
            updateContent { it.copy(favorite = favorite) }
        }
        viewModelScope.launch { loadRecommendations(vod.typeId) }
    }

    /**
     * 相关推荐。旧页在详情成功后按同类型再拉一次列表
     * 失败分支是空 `onError`（`:874-876`）——
     * 推荐只是附加内容，拉不到就把列表留空，绝不把详情换成错误态。
     * `typeId` 为 0 表示后端没给类型，直接不请求：旧页这时会带 type=0 拉回全站列表。
     */
    private suspend fun loadRecommendations(typeId: Int) {
        if (typeId <= 0) {
            updateContent { it.copy(recommendationsLoading = false) }
            return
        }
        val items = when (val result = deps.vodRepository.recommend(typeId)) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> emptyList()
        }
        updateContent { it.copy(recommendations = items, recommendationsLoading = false) }
    }

    /**
     * 详情加载失败：首屏给整页错误态；已有内容时只弹提示，
     * 不把用户正在看的页面清空。
     */
    private fun applyDetailFailure(error: AppError, previous: DetailUiState.Content?) {
        val message = error.toDetailMessage()
        if (previous == null) {
            _state.value = DetailUiState.Error(message)
            return
        }
        updateContent { it.copy(isRefreshing = false) }
        notify(message)
    }

    /**
     * 进页面时的收藏判定。未登录直接判 `SignedOut`，不发请求——
     * 旧页虽然也先 `isLogin()` 一下，但失败分支是空 catch，
     * 游客会看到"追剧"按钮却点不动。
     */
    private suspend fun loadFavoriteState(): DetailFavoriteState {
        if (deps.authRepository.restore() == null) return DetailFavoriteState.SignedOut
        return when (val listed = deps.favoriteRepository.list(FIRST_PAGE, FAVORITE_PROBE_LIMIT)) {
            is AppResult.Success -> favoriteStateOf(listed.value.any { it.vodId == vodId })
            // 收藏列表失败不阻断详情：按未收藏展示，点收藏时会重新查询再决定加减。
            is AppResult.Failure -> DetailFavoriteState.NotFavorited
        }
    }

    private suspend fun requestToggle(previous: DetailFavoriteState) {
        if (deps.authRepository.restore() == null) {
            updateFavorite(DetailFavoriteState.SignedOut)
            notify(SIGN_IN_REQUIRED)
            return
        }
        when (val listed = deps.favoriteRepository.list(FIRST_PAGE, FAVORITE_PROBE_LIMIT)) {
            is AppResult.Success -> requestToggle(listed.value.any { it.vodId == vodId })
            is AppResult.Failure -> {
                updateFavorite(previous)
                notify(listed.error.toDetailMessage())
            }
        }
    }

    private suspend fun requestToggle(favorited: Boolean) {
        val target = if (favorited) {
            deps.favoriteRepository.remove(listOf(vodId))
        } else {
            deps.favoriteRepository.add(vodId)
        }
        when (target) {
            is AppResult.Success -> applyFavorite(
                next = favoriteStateOf(!favorited),
                message = if (favorited) UNFAVORITED else FAVORITED,
            )

            is AppResult.Failure -> applyFavorite(
                next = favoriteStateOf(favorited),
                message = target.error.toDetailMessage(),
            )
        }
    }

    private fun applyFavorite(next: DetailFavoriteState, message: String) {
        updateFavorite(next)
        notify(message)
    }

    private fun updateFavorite(favorite: DetailFavoriteState) =
        updateContent { it.copy(favorite = favorite) }

    private fun updateContent(transform: (DetailUiState.Content) -> DetailUiState.Content) {
        _state.update { current ->
            if (current is DetailUiState.Content) transform(current) else current
        }
    }

    private fun notify(message: String) {
        _events.trySend(DetailEvent.Toast(message))
    }

    companion object {
        fun factory(deps: DetailDeps, vodId: Int): ViewModelProvider.Factory = viewModelFactory {
            initializer { DetailViewModel(deps = deps, vodId = vodId) }
        }
    }
}

/** 优先沿用当前选中的源；它没有剧集时退到第一个有剧集的源。 */
private fun resolveSourceIndex(vod: Vod, preferred: Int): Int {
    val usable = vod.playSources.getOrNull(preferred)?.takeIf { it.episodes.isNotEmpty() }
    return if (usable != null) preferred else vod.playSources.indexOfFirst { it.episodes.isNotEmpty() }
}

private fun favoriteStateOf(favorited: Boolean): DetailFavoriteState =
    if (favorited) DetailFavoriteState.Favorited else DetailFavoriteState.NotFavorited

private fun DetailUiState.Content.withSource(index: Int): DetailUiState.Content {
    val lastIndex = vod.playSources.lastIndex
    val target = vod.playSources.getOrNull(index)
    // 没有源 / 下标越界都原样返回；合成一个出口，detekt 的 ReturnCount 上限是 2
    if (lastIndex < 0 || target == null) return this
    // 换源保留同一个集号：不同源的集数可能对不齐，找不到就退回第 1 集。
    // 不能一律重置成第 1 集——那样切源等于把用户踢回开头，还得再点一次集数。
    val nextEpisodeIndex = target.episodes.indexOfFirst {
        it.episodeNumber == selectedEpisode?.episodeNumber
    }
    return copy(
        sourceIndex = index.coerceIn(0, lastIndex),
        episodeIndex = nextEpisodeIndex.coerceAtLeast(0),
        // 换源后集数可能完全不同，回到收起态，避免上一部的"展开全部"漏到新源上。
        isEpisodeGridExpanded = false,
    )
}

private fun DetailUiState.Content.withEpisode(index: Int): DetailUiState.Content {
    val lastIndex = source?.episodes?.lastIndex ?: -1
    return if (lastIndex < 0) this else copy(episodeIndex = index.coerceIn(0, lastIndex))
}

/** 收藏列表固定第 1 页（旧  传 page=1、limit=100）。 */
private const val FIRST_PAGE = 1

/** 判断"是否已收藏"时一次取 100 条，与旧详情页一致；只查 10 条会误判。 */
private const val FAVORITE_PROBE_LIMIT = 100
private const val SIGN_IN_REQUIRED = "请先登录"
private const val FAVORITED = "已收藏"
private const val UNFAVORITED = "已取消收藏"
private const val EMPTY_SOURCES = "该影片暂时没有可播放的剧集"

/**
 * 错误 → 用户可读文案。
 *
 * 与收藏页 `toPageMessage()` 同口径，但那是 feature:user 的内部函数，跨模块不可见，
 * 单独一份；Business 必须透传后端 msg，否则用户不知道是"影片下架"还是"没权限"。
 */
internal fun AppError.toDetailMessage(): String = when (this) {
    is AppError.Business -> message.ifBlank { "请求被拒绝，请稍后重试" }
    is AppError.Network -> "网络异常，请检查网络后重试"
    is AppError.Http -> "服务异常（$code）"
    is AppError.Serialization -> "数据解析失败，请稍后重试"
    is AppError.Unknown -> "未知错误，请稍后重试"
}
