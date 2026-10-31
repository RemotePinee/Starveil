package com.starveil.feature.shortplay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.common.getOrNull
import com.starveil.core.data.ShortPlayRepository
import com.starveil.core.model.ShortPlay
import com.starveil.core.model.ShortPlayType
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 随机短剧信息流。快照先显示，失败不清空；代际校验隔离取消后的迟到结果。 */
class ShortPlayViewModel(
    private val repository: ShortPlayRepository,
    private val pageSize: Int = PAGE_SIZE,
    /** 抽屉里的全量分类（后台「短剧分类 id」）。 */
    private val types: List<ShortPlayType> = emptyList(),
    /** 顶部横滑条的分类（后台「短剧顶部 tab 分类 id」，一般 3-6 个）；空则顶部退回 [types]。 */
    val tabs: List<ShortPlayType> = emptyList(),
    private val searchTypeId: Int = 0,
    /** 进页默认流用的父分类（后台「短剧搜索分类 id」）；0 = 没配，退回第一个子分类。 */
    private val startTypeId: Int = 0,
    private val shuffle: (List<ShortPlay>) -> List<ShortPlay> = { it.shuffled() },
) : ViewModel() {
    /**
     * 进页的默认流：配了父分类就直接拉父分类（服务端查父分类会带上全部子分类），
     * 没配才退回抽屉里的第一个子分类。
     */
    private var selectedTypeId: Int = if (startTypeId > 0) startTypeId else types.firstOrNull()?.id ?: 0
    private val _state = MutableStateFlow<ShortPlayUiState>(ShortPlayUiState.Loading(types, selectedTypeId))
    val state: StateFlow<ShortPlayUiState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var generation = 0L
    private var collectionVersion = 0L
    private var pinnedPlaybackId: Int? = null
    /** 父分类兜底是否已经用过：整页生命周期只退一次，见 [fallbackToFirstType]。 */
    private var fallbackUsed = false
    private val _suggestions = MutableStateFlow<List<ShortPlay>>(emptyList())
    val suggestions: StateFlow<List<ShortPlay>> = _suggestions.asStateFlow()
    private var suggestJob: Job? = null
    private var searchKeyword: String = ""

    init {
        seedFromSnapshot()
        loadFirstPage(resetPosition = false)
    }

    private fun seedFromSnapshot() {
        val cached = repository.cachedShortPlay(selectedTypeId.takeIf { it > 0 }).orEmpty()
        if (cached.isNotEmpty()) applyFirstPage(cached, resetPosition = true)
    }

    fun refresh() = loadFirstPage(resetPosition = true)

    /** 搜索候选：只更新候选列表，不动 feed 与播放位置；播放必须由 [playFromSearch] 触发。 */
    fun suggest(keyword: String) {
        suggestJob?.cancel()
        val next = keyword.trim()
        if (next.isEmpty()) {
            _suggestions.value = emptyList()
            return
        }
        suggestJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _suggestions.value = when (val result = searchAllTypes(next, FIRST_PAGE)) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> emptyList()
            }
        }
    }

    fun playFromSearch(play: ShortPlay) {
        suggestJob?.cancel()
        _suggestions.value = emptyList()
        collectionVersion++
        _state.value = ShortPlayUiState.Content(
            plays = listOf(play), types = types, selectedTypeId = selectedTypeId,
            endReached = true, currentPlayId = play.id, collectionVersion = collectionVersion,
        )
    }

    fun clearSearch() { suggestJob?.cancel(); _suggestions.value = emptyList() }

    /**
     * 全屏播放期间钉住当前列表：取消在途首屏并作废代际，保留已有内容与顺序，
     * 追加仍按原顺序接在尾部；解 pin 不主动刷新，等用户 refresh 或离开该项。
     */
    fun pinPlayback(playId: Int?) {
        if (playId == null) {
            pinnedPlaybackId = null
            return
        }
        pinnedPlaybackId = playId
        loadJob?.cancel()
        generation++
        _state.update { current ->
            val content = current as? ShortPlayUiState.Content ?: return@update current
            val anchor = playId.takeIf { id -> content.plays.any { it.id == id } } ?: content.currentPlayId
            content.copy(currentPlayId = anchor, isRefreshing = false, isLoadingMore = false)
        }
    }

    fun onPageSettled(playId: Int) {
        if (pinnedPlaybackId?.let { it != playId } == true) return
        _state.update { current ->
            val content = current as? ShortPlayUiState.Content ?: return@update current
            if (content.plays.any { it.id == playId }) content.copy(currentPlayId = playId) else content
        }
    }

    fun retry() {
        val content = _state.value as? ShortPlayUiState.Content
        if (content?.appendError != null) loadMore() else loadFirstPage(resetPosition = false)
    }

    fun loadMore() {
        val content = _state.value as? ShortPlayUiState.Content ?: return
        val busy = content.isLoadingMore || content.isRefreshing
        // 搜索结果是一份固定命中集，后端不分页，再翻只会重复请求第一页。
        val noMore = content.endReached || searchKeyword.isNotEmpty()
        if (busy || noMore) return
        val nextPage = content.page + 1
        val requestType = selectedTypeId
        val requestGeneration = generation
        _state.value = content.copy(isLoadingMore = true, appendError = null)
        loadJob = viewModelScope.launch {
            val result = fetchPlays(requestType.takeIf { it > 0 }, nextPage)
            if (!isCurrentRequest(requestType, requestGeneration)) return@launch
            when (result) {
                is AppResult.Success -> _state.update { it.appendPage(nextPage, result.value, pageSize, shuffle) }
                is AppResult.Failure -> _state.update { it.appendFailed(result.error.toUserMessage()) }
            }
        }
    }

    fun selectType(typeId: Int) {
        // 钉在播放里、或点的就是当前分类，都不换流。
        if (pinnedPlaybackId != null || typeId == selectedTypeId) return
        // 不能只认抽屉的 types：顶部 tab 与进页的父分类都不在那一组里。
        // 抽屉分类、顶部 tab、进页父分类三者都算合法目标，其余 id 直接忽略。
        if (!isSelectable(typeId)) return
        selectedTypeId = typeId
        searchKeyword = ""
        // 不要先打回整屏 Loading：那会让切分类先闪一屏黑底转圈（外层底色是 scrimStrong）。
        // 保留当前内容，等新分类的快照或网络结果回来原地替换——与首页切分类同口径。
        seedFromSnapshot()
        loadFirstPage(resetPosition = false)
    }

    /** 抽屉分类、顶部 tab 与进页父分类都是合法目标；脏 id 不该把当前流打断。 */
    private fun isSelectable(typeId: Int): Boolean =
        typeId == startTypeId || types.any { it.id == typeId } || tabs.any { it.id == typeId }

    private fun loadFirstPage(resetPosition: Boolean) {
        if (pinnedPlaybackId != null) return
        val requestGeneration = ++generation
        val requestType = selectedTypeId
        loadJob?.cancel()
        _state.update { it.refreshing(types, selectedTypeId) }
        loadJob = viewModelScope.launch {
            val result = fetchPlays(requestType.takeIf { it > 0 }, FIRST_PAGE)
            if (!isCurrentRequest(requestType, requestGeneration)) return@launch
            when (result) {
                is AppResult.Success -> applyFirstPageOrFallback(result.value, resetPosition)
                is AppResult.Failure ->
                    _state.update { it.failed(result.error.toUserMessage(), types, selectedTypeId) }
            }
        }
    }

    private fun isCurrentRequest(typeId: Int, requestGeneration: Long): Boolean =
        typeId == selectedTypeId && requestGeneration == generation

    /**
     * 父分类兜底：进页按父分类拉全部子分类，首屏**成功但一条都没有**（父分类 id 填错、
     * 或这个父分类下确实没内容）时换成第一个子分类再拉一次。不这么做页面就停在
     * 「暂无短剧」，用户只能自己点开抽屉换分类——而这时他根本不知道是配置错了。
     * 只在 [fallbackToFirstType] 里退一次，见那里的说明。
     */
    private fun applyFirstPageOrFallback(plays: List<ShortPlay>, resetPosition: Boolean) {
        if (plays.isEmpty() && fallbackToFirstType()) return
        applyFirstPage(plays, resetPosition)
    }

    /**
     * 退回抽屉里的第一个子分类，返回是否真的退了。
     *
     * 三个条件缺一不可：还没退过（[fallbackUsed]）、进页确实用了父分类、当前仍在父分类上。
     * 只退一次是硬要求：父分类和第一个子分类都空时不能继续往后挨个试，
     * 那是十几个并发请求换一屏"暂无短剧"。
     */
    private fun fallbackToFirstType(): Boolean {
        val first = types.firstOrNull()?.id.takeIf { isFallbackDue() } ?: return false
        fallbackUsed = true
        selectedTypeId = first
        seedFromSnapshot()
        // 这段代码就跑在 loadJob 的协程里：先清空再走常规首屏路径，
        // 否则 loadFirstPage 里的 loadJob?.cancel() 会把正在跑的自己取消掉。
        loadJob = null
        loadFirstPage(resetPosition = false)
        return true
    }

    /** 兜底只在「还没退过 + 进页确实用了父分类 + 当前仍停在父分类上」时成立。 */
    private fun isFallbackDue(): Boolean =
        !fallbackUsed && startTypeId > 0 && selectedTypeId == startTypeId

    /** 搜索与分类流共用同一条首屏/追加路径，只有数据源不同。 */
    private suspend fun fetchPlays(typeId: Int?, page: Int): AppResult<List<ShortPlay>> =
        if (searchKeyword.isEmpty()) {
            repository.feed(typeId, page, pageSize)
        } else {
            // 搜索绕开当前分类：配了父分类就一次带全子分类，没配才按后台配的
            // 几个短剧分类各搜一遍。只搜当前分类会漏掉挂在其它分类下的同名剧。
            searchAllTypes(searchKeyword, page)
        }

    /** 搜索入口：配了父分类就一次请求，没配才走 [searchByManyTypes] 逐个查。 */
    private suspend fun searchAllTypes(keyword: String, page: Int): AppResult<List<ShortPlay>> =
        if (searchTypeId > 0) {
            // 服务端按父分类查会自动带上全部子分类，所以一次请求就够
            repository.search(keyword, searchTypeId, page, pageSize)
        } else {
            repository.searchByManyTypes(types, keyword, page, pageSize)
        }

    private fun applyFirstPage(plays: List<ShortPlay>, resetPosition: Boolean) {
        if (pinnedPlaybackId != null) return
        val previousId = (_state.value as? ShortPlayUiState.Content)?.currentPlayId
        val shuffled = shuffle(plays).distinctBy { it.id }
        val retainedId = previousId?.takeIf { id -> !resetPosition && shuffled.any { it.id == id } }
        collectionVersion++
        _state.value = if (shuffled.isEmpty()) {
            ShortPlayUiState.Empty(
                message = if (searchKeyword.isEmpty()) "暂无短剧" else "没有找到「$searchKeyword」",
                types = types,
                selectedTypeId = selectedTypeId,
            )
        } else {
            ShortPlayUiState.Content(
                plays = shuffled,
                types = types,
                selectedTypeId = selectedTypeId,
                endReached = plays.size < pageSize,
                currentPlayId = retainedId ?: shuffled.first().id,
                collectionVersion = collectionVersion,
            )
        }
    }

    companion object {
        const val PAGE_SIZE = 20

        fun factory(
            repository: ShortPlayRepository,
            types: List<ShortPlayType> = emptyList(),
            tabs: List<ShortPlayType> = emptyList(),
            searchTypeId: Int = 0,
            startTypeId: Int = 0,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ShortPlayViewModel(
                    repository,
                    types = types,
                    tabs = tabs,
                    searchTypeId = searchTypeId,
                    startTypeId = startTypeId,
                )
            }
        }
    }
}

/** 首屏在途：已有内容就原地打刷新标记，还没有内容才落整屏 Loading。 */
private fun ShortPlayUiState.refreshing(types: List<ShortPlayType>, selectedTypeId: Int): ShortPlayUiState =
    if (this is ShortPlayUiState.Content) {
        copy(isRefreshing = true, isLoadingMore = false, appendError = null, refreshError = null)
    } else {
        ShortPlayUiState.Loading(types, selectedTypeId)
    }

/** 首屏失败：有内容只挂提示条，没内容才落整屏错误态（分类仍保留，能从抽屉切走）。 */
private fun ShortPlayUiState.failed(
    message: String,
    types: List<ShortPlayType>,
    selectedTypeId: Int,
): ShortPlayUiState = if (this is ShortPlayUiState.Content) {
    copy(isRefreshing = false, refreshError = message)
} else {
    ShortPlayUiState.Error(message, types, selectedTypeId)
}

/** 追加一页：合并去重，顺带更新是否到底。 */
private fun ShortPlayUiState.appendPage(
    page: Int,
    plays: List<ShortPlay>,
    pageSize: Int,
    shuffle: (List<ShortPlay>) -> List<ShortPlay>,
): ShortPlayUiState = if (this is ShortPlayUiState.Content) {
    copy(
        plays = (this.plays + shuffle(plays)).distinctBy { it.id },
        page = page,
        isLoadingMore = false,
        endReached = plays.size < pageSize,
        appendError = null,
    )
} else {
    this
}

/** 追加失败：只挂错误文案，已经看到的内容留在原地。 */
private fun ShortPlayUiState.appendFailed(message: String): ShortPlayUiState =
    if (this is ShortPlayUiState.Content) copy(isLoadingMore = false, appendError = message) else this

private fun AppError.toUserMessage(): String = when (this) {
    is AppError.Network -> "网络异常，点此重试"
    is AppError.Http -> "服务异常（$code）"
    is AppError.Business -> message.ifBlank { "请求被拒绝" }
    is AppError.Serialization -> "数据解析失败"
    is AppError.Unknown -> "未知错误"
}

/**
 * 后台没配搜索父分类时的退路：按每个短剧分类各搜一次再合并去重。
 *
 * 并行而非串行：串行时 3 个分类就是 3 个往返，按键到出候选会明显卡。
 * 只有一个分类成功也返回合并结果，全失败才把错误交给调用方。
 */
private suspend fun ShortPlayRepository.searchByManyTypes(
    types: List<ShortPlayType>,
    keyword: String,
    page: Int,
    pageSize: Int,
): AppResult<List<ShortPlay>> {
    val targets = types.map { it.id }.filter { it > 0 }.ifEmpty { listOf(0) }
    val results = coroutineScope {
        targets.map { id ->
            async { search(keyword, id.takeIf { it > 0 }, page, pageSize) }
        }.awaitAll()
    }
    val merged = results.mapNotNull { it.getOrNull() }.flatten()
    if (merged.isEmpty()) {
        results.filterIsInstance<AppResult.Failure>().firstOrNull()?.let { return AppResult.Failure(it.error) }
    }
    return AppResult.Success(merged.distinctBy { it.id })
}
