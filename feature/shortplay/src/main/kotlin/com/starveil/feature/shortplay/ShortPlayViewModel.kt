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
    /** 全量分类（后台「短剧分类 id」）：顶部横条没配时退回这一组，分类页也用这一份。 */
    private val types: List<ShortPlayType> = emptyList(),
    /** 顶部横滑条的分类（后台「短剧顶部 tab 分类 id」，一般 3-6 个）；空则顶部退回 [types]。 */
    val tabs: List<ShortPlayType> = emptyList(),
    private val searchTypeId: Int = 0,
    private val shuffle: (List<ShortPlay>) -> List<ShortPlay> = { it.shuffled() },
) : ViewModel() {
    /**
     * 进页默认分类：顶部第一个 tab → 全量分类第一个 → 0（「全部」），见 [defaultShortPlayTypeId]。
     *
     * 顶部条只认这一个值判选中，所以进页时第一个 chip 本来就是选中的，不必等用户点。
     */
    private var selectedTypeId: Int = defaultShortPlayTypeId(tabs, types)
    private val _state = MutableStateFlow<ShortPlayUiState>(ShortPlayUiState.Loading(types, selectedTypeId))
    val state: StateFlow<ShortPlayUiState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var generation = 0L
    private var collectionVersion = 0L
    private var pinnedPlaybackId: Int? = null
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
            endReached = true, currentPlayId = play.id,
            collectionVersion = collectionVersion, pagesVersion = nextPagesVersion(),
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
                is AppResult.Success -> _state.update {
                    it.appendPage(nextPage, shuffle(result.value), pageSize, ::nextCollectionVersion)
                }
                is AppResult.Failure -> _state.update { it.appendFailed(result.error.toUserMessage()) }
            }
        }
    }

    /** 列表版本号只跟上一个 state 比：原地换掉 content 也要变，pages 才会重算。 */
    private fun nextPagesVersion(): Long =
        ((_state.value as? ShortPlayUiState.Content)?.pagesVersion ?: -1L) + 1L

    /** 头部被裁掉会移动 pager 下标，只有这一种追加才动 collectionVersion。 */
    private fun nextCollectionVersion(): Long = ++collectionVersion

    fun selectType(typeId: Int) {
        // 钉在播放里、或点的就是当前分类，都不换流。
        if (pinnedPlaybackId != null || typeId == selectedTypeId) return
        // 不能只认全量分类：顶部 tab 不在那一组里。
        // 全量分类与顶部 tab 都算合法目标，其余 id 直接忽略。
        if (!isSelectable(typeId)) return
        selectedTypeId = typeId
        searchKeyword = ""
        // 新分类的本地快照先铺上：切过去第一帧就是那一类的内容——模糊封面、剧名、
        // 分类药丸立刻在屏上，视频由那一页自己加载。没有快照才落到占位层（只有底色）。
        // 代价是快照过期时（这一分类如今空了）会先闪一屏 feed 再跳空态；
        // 切分类先露黑底比这个更难看，按"先给内容"取舍。
        seedFromSnapshot()
        loadFirstPage(resetPosition = false)
    }

    /** 全量分类与顶部 tab 都是合法目标；脏 id 不该把当前流打断。 */
    private fun isSelectable(typeId: Int): Boolean =
        types.any { it.id == typeId } || tabs.any { it.id == typeId }

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
                is AppResult.Success -> applyFirstPage(result.value, resetPosition)
                is AppResult.Failure ->
                    _state.update { it.failed(result.error.toUserMessage(), types, selectedTypeId) }
            }
        }
    }

    private fun isCurrentRequest(typeId: Int, requestGeneration: Long): Boolean =
        typeId == selectedTypeId && requestGeneration == generation

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
        // 首屏 / 刷新 / 快照重校验都走这一条：整份 Content 重建（列表、分类、选中项、
        // 页码标记全部按这次结果重来）。只换列表的话，后台改动要"刷新 → 再杀一次应用"才看得见。
        val previousId = (_state.value as? ShortPlayUiState.Content)?.currentPlayId
        val shuffled = shuffle(plays).distinctBy { it.id }
        val retainedId = previousId?.takeIf { id -> !resetPosition && shuffled.any { it.id == id } }
        collectionVersion++
        _state.value = if (shuffled.isEmpty()) {
            ShortPlayUiState.Empty(
                message = if (searchKeyword.isEmpty()) "暂无短剧" else "没有找到「$searchKeyword」",
                // 空态没有按钮，这一步该做什么由文案说清楚：分类空就换分类，搜不到就换词。
                hint = if (searchKeyword.isEmpty()) "换个分类看看，或稍后再来" else "换个关键词再试试",
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
                pagesVersion = nextPagesVersion(),
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
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ShortPlayViewModel(
                    repository,
                    types = types,
                    tabs = tabs,
                    searchTypeId = searchTypeId,
                )
            }
        }
    }
}

/**
 * 短剧页进页默认分类：顶部 tab 第一个 → 全量分类第一个 → 0（「全部」）。
 *
 * 只在这里算一次：顶部横条的选中态、首屏请求的分类、快照预热都拿它的结果，
 * 页面上不会出现「拉的是 A、选中态画的是 B」。
 *
 * `0` 是「全部」的唯一表示（仓库按 null/0 落到后台配的默认分类），
 * 两个列表都空时才落到它，与改造前一致。
 */
fun defaultShortPlayTypeId(tabs: List<ShortPlayType>, types: List<ShortPlayType>): Int =
    (tabs.firstOrNull { it.id > 0 } ?: types.firstOrNull { it.id > 0 })?.id ?: 0

/**
 * 追加一页：合并去重，超出软上限就裁回窗口；不是 `Content` 原样返回。
 *
 * 两个版本号语义不同，由调用方用回调给：`collectionVersion` 只在头部被裁时变
 * （pager 要重新定位），`pagesVersion` 每次换列表都变（信息流页据此重算分页）。
 */
private fun ShortPlayUiState.appendPage(
    page: Int,
    incoming: List<ShortPlay>,
    pageSize: Int,
    nextVersion: () -> Long,
): ShortPlayUiState = if (this is ShortPlayUiState.Content) {
    val merged = (plays + incoming).distinctBy { it.id }
    val window = if (merged.size > PLAY_WINDOW_MAX) keepWindowAround(merged, currentPlayId) else merged
    copy(
        plays = window,
        page = page,
        isLoadingMore = false,
        endReached = incoming.size < pageSize,
        appendError = null,
        // 从头部丢了一截就动了 pager 的下标：换版本号让它重新对到当前播放项
        collectionVersion = if (window.size < merged.size) nextVersion() else collectionVersion,
        pagesVersion = pagesVersion + 1,
    )
} else {
    this
}

/** 首屏在途：已有内容就原地打刷新标记；换分类时原地等，只有首次加载才落整屏 Loading。 */
private fun ShortPlayUiState.refreshing(types: List<ShortPlayType>, selectedTypeId: Int): ShortPlayUiState =
    when {
        this is ShortPlayUiState.Content ->
            copy(isRefreshing = true, isLoadingMore = false, appendError = null, refreshError = null)
        // 已经换到别的分类：整屏改成转圈会先闪一下，占位层已经兜住这一段。
        this.selectedTypeId != selectedTypeId -> this
        // 同一分类的「换一批 / 重试」仍给转圈反馈。
        else -> ShortPlayUiState.Loading(types, selectedTypeId)
    }

/** 首屏失败：有内容只挂提示条，没内容才落整屏错误态（分类仍保留，能从顶部切走）。 */
private fun ShortPlayUiState.failed(
    message: String,
    types: List<ShortPlayType>,
    selectedTypeId: Int,
): ShortPlayUiState = if (this is ShortPlayUiState.Content) {
    copy(isRefreshing = false, refreshError = message)
} else {
    ShortPlayUiState.Error(message, types, selectedTypeId)
}

/**
 * 把 [plays] 收进长度不超过 [limit] 的窗口，且窗口必须包含 [keepId]。
 *
 * 默认从头部丢最老的：用户往后刷时正片在尾部，丢的一定是已经看过的。
 * 但当前播放项前面要留 [PLAY_WINDOW_LOOKBACK] 条——往回刷时那一批还在，
 * 不会刚划上去就发现"上面没了"。播放项贴着列表头时改从尾部丢，
 * 否则窗口里一条不剩，正在播的那条也会被裁掉。
 */
internal fun keepWindowAround(
    plays: List<ShortPlay>,
    keepId: Int?,
    limit: Int = PLAY_WINDOW,
): List<ShortPlay> {
    if (limit <= 0 || plays.size <= limit) return plays
    val anchor = plays.indexOfFirst { it.id == keepId }.coerceAtLeast(0)
    val latestStart = plays.size - limit
    val start = (anchor - PLAY_WINDOW_LOOKBACK).coerceIn(0, latestStart)
    return plays.subList(start, start + limit).toList()
}

/** 列表软上限：一旦超过 [PLAY_WINDOW_MAX] 就裁回 [PLAY_WINDOW]。 */
internal const val PLAY_WINDOW = 400
internal const val PLAY_WINDOW_MAX = 500

/** 裁剪时给当前播放项留的回看余量（条）。 */
private const val PLAY_WINDOW_LOOKBACK = 100

/** 追加失败：只挂错误文案，已经看到的内容留在原地。 */
private fun ShortPlayUiState.appendFailed(message: String): ShortPlayUiState =
    if (this is ShortPlayUiState.Content) copy(isLoadingMore = false, appendError = message) else this

/** 错误文案：剧场页与分类页共用同一套口径。 */
internal fun AppError.toUserMessage(): String = when (this) {
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
