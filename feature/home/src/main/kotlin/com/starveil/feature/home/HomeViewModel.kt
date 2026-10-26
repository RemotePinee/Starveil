package com.starveil.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.data.ContinueWatchingRepository
import com.starveil.core.data.HomeSnapshotStore
import com.starveil.core.data.LivePage
import com.starveil.core.data.LiveRepository
import com.starveil.core.data.StartupRepository
import com.starveil.core.data.TopicRepository
import com.starveil.core.data.VodRepository
import com.starveil.core.model.ContinueWatching
import com.starveil.core.model.HomeBanner
import com.starveil.core.model.HomeBlock
import com.starveil.core.model.HomeLink
import com.starveil.core.model.HomeModule
import com.starveil.core.model.HomeSpotlight
import com.starveil.core.model.HomeVariant
import com.starveil.core.model.LiveChannel
import com.starveil.core.model.SkinId
import com.starveil.core.model.Skins
import com.starveil.core.model.StartupConfig
import com.starveil.core.model.Topic
import com.starveil.core.model.TopicDetail
import com.starveil.core.model.VodType
import com.starveil.core.model.Vod
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 首页状态持有者。只调仓库，不发 HTTP（AGENTS.md 第 3 节）。
 *
 * 加载顺序：先取启动配置（决定皮肤与列数），再取列表。启动配置失败不阻断首页——
 * 在关键字段缺失时直接退出进程，
 * 降级成默认皮肤继续跑。
 */
class HomeViewModel(
    private val repository: VodRepository,
    private val startupRepository: StartupRepository,
    private val continueWatchingRepository: ContinueWatchingRepository,
    /** 专题列表；默认空实现，其它入口构造本 VM 时不必提供。 */
    private val topicRepository: TopicRepository = EmptyTopicRepository,
    /** 直播列表；同上。 */
    private val liveRepository: LiveRepository = EmptyLiveRepository,
    /**
     * 首页首帧快照：从仓库取，不额外占一个构造参数（工厂参数已经到 detekt 上限）。
     * 有它就在构造期同步铺出 Content，冷启动不再先转圈。
     */
    private val snapshot: HomeSnapshotStore? = repository.homeSnapshot(),
) : ViewModel() {

    // 启动配置已预取过：首帧骨架直接用缓存的模块顺序，避免进来后重排（快捷入口、热度榜"晚一步出现"）
    private val cachedStartup: StartupConfig? = startupRepository.cached()
    private val _state = MutableStateFlow(homeInitialState(snapshot, cachedStartup))
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var variant: HomeVariant = HomeVariant.CARD_GRID_C
    private var columns: Int = StartupConfig.DEFAULT_HOME_COLUMNS
    private var types: List<VodType> = emptyList()
    private var selectedTypeId: Int = 0
    private var layoutLoaded = false
    private var banners: List<HomeBanner> = emptyList()
    private var blocks: List<HomeBlock> = emptyList()
    private var links: List<HomeLink> = emptyList()
    private var featured: HomeSpotlight = HomeSpotlight()
    private var hot: HomeSpotlight = HomeSpotlight()
    private var guess: HomeSpotlight = HomeSpotlight()
    /** 分类 → (热度榜, 猜你喜欢)；每分类只请求一次，切回直接用。 */
    private val spotlightsByType = mutableMapOf<Int, Pair<HomeSpotlight, HomeSpotlight>>()
    /** 列表缓存：预热、在飞去重、切分类时瞬间取用。 */
    private val feedCache = HomeFeedCache(viewModelScope) { typeId -> repository.list(type = typeId) }
    /** 等当前分类结果的句柄：切换时取消的是"等待"，在飞的请求留给缓存用。 */
    private var itemsJob: Job? = null
    private var topics: List<Topic> = emptyList()
    private var lives: List<LiveChannel> = emptyList()
    private var pushCount: Int = 0
    private var pushEnabled: Boolean = false
    private var searchHot: List<String> = emptyList()
    private var modules: List<HomeModule> = cachedStartup.moduleOrder()
    private var continueWatching: List<ContinueWatching> = emptyList()

    init {
        load(refreshing = false)
    }

    /** 当前生效的布局配置，写回状态时一次性传过去。 */
    private fun homeLayout() = HomeLayout(
        variant = variant,
        columns = columns,
        modules = modules,
        searchHot = searchHot,
        pushEnabled = pushEnabled,
    )

    fun refresh() = load(refreshing = true)

    /**
     * 切换顶部 Tab。
     *
     * 三件事必须同时发生，否则就是"点了没反应"：高亮换掉、列表换成目标分类的内容（命中缓存
     * 直接铺，没缓存就先清空）、旧请求取消。绝不能把上一个分类的列表留在屏幕上等网络——
     * 那段时间用户看到的是"切了但没变"。
     */
    fun selectType(typeId: Int) {
        if (typeId == selectedTypeId) return
        selectedTypeId = typeId
        val current = _state.value
        if (current !is HomeUiState.Content) {
            load(refreshing = false)
            return
        }
        val cached = feedCache.cached(typeId)
        _state.value = current.copy(
            selectedTypeId = typeId,
            items = cached.orEmpty(),
            // 没缓存就先上骨架：预取还没回来时用户看到的是"正在换"，不是上一个分类的内容
            isSwitching = cached == null,
        )
        itemsJob?.cancel()
        itemsJob = viewModelScope.launch { loadItems(typeId) }
        loadSpotlights(typeId)
    }

    private fun load(refreshing: Boolean) {
        if (refreshing) {
            _state.update { current ->
                if (current is HomeUiState.Content) current.copy(isRefreshing = true) else current
            }
        } else if (_state.value !is HomeUiState.Content) {
            // 已经有快照铺出来的 Content 时不要打回 Loading：那等于把刚显示的首页又换成转圈
            _state.value = HomeUiState.Loading()
        }

        viewModelScope.launch {
            if (!layoutLoaded) {
                loadLayout()
                // 配置（列数 / 皮肤 / 模块 / 热词）刚更新过，要写回已经铺出来的 Content。
                // 少了这一步，网络回来的新配置只落在成员变量和快照文件里，界面继续用快照里的
                // 旧值，必须再冷启动一次才看到变化——表现就是"后台改完要杀两次"。
                _state.update { current -> current.withLayout(homeLayout()) }
                types = repository.homePrimaryTypes()
                loadContent()
                layoutLoaded = true
            } else if (refreshing) {
                loadContent()
            }
            loadItems()
            // 首屏已经铺出来了才预热其它分类：这一步不参与首屏，慢一点也没关系
            prefetchTypes()
        }
    }

    /** 启动配置失败时保持默认皮肤，不把错误抛给 UI。 */
    private suspend fun loadLayout() {
        when (val result = startupRepository.load()) {
            is AppResult.Success -> {
                variant = Skins.home(result.value.skin.skin)
                columns = result.value.homeCardColumns
                modules = result.value.homeModules.ifEmpty { HomeModule.entries.toList() }
                searchHot = result.value.searchHot
                pushEnabled = result.value.pushEnabled
            }

            is AppResult.Failure -> {
                variant = Skins.home(SkinId.DEFAULT)
                columns = StartupConfig.DEFAULT_HOME_COLUMNS
                searchHot = emptyList()
                pushEnabled = false
            }
        }
        snapshot?.saveLayout(variant, columns)
    }

    /** 首页内容位：轮播/专区/快捷入口/三个卡片位/专题/直播/继续观看，任一失败只降级该模块。 */
    private suspend fun loadContent() {
        banners = (repository.banners() as? AppResult.Success)?.value.orEmpty()
        blocks = (repository.blocks() as? AppResult.Success)?.value.orEmpty()
        links = (repository.links() as? AppResult.Success)?.value.orEmpty()
        // 失败/未配置都落成空配置：UI 据此回退到列表切片，不让整块卡片位消失
        featured = (repository.featured() as? AppResult.Success)?.value ?: HomeSpotlight()
        hot = (repository.hot() as? AppResult.Success)?.value ?: HomeSpotlight()
        guess = (repository.guess() as? AppResult.Success)?.value ?: HomeSpotlight()
        pushCount = repository.homePushCount(pushEnabled)
        // 首页这一套也进缓存：从分类切回 0 时不重复请求
        spotlightsByType[0] = hot to guess
        topics = topicRepository.homeTopics(modules)
        lives = liveRepository.homeLives(modules)
        continueWatching = continueWatchingRepository.homeContinueWatching(modules)
    }

    private suspend fun loadItems(typeId: Int = selectedTypeId) {
        when (val result = feedCache.load(typeId)) {
            is AppResult.Success -> applyList(typeId, result.value)
            is AppResult.Failure -> applyListFailure(typeId, result.error.toMessage())
        }
    }

    /** 列表到手：只写当前分类的 UI，切走的分类结果留给缓存自己用。 */
    private fun applyList(typeId: Int, list: List<Vod>) {
        // 用户已经切走：结果留给缓存，别改屏幕上那个分类
        if (typeId != selectedTypeId) return
        val previous = _state.value as? HomeUiState.Content
        _state.value = when {
            // 分类行、轮播、模块都沿用当前状态：切分类只换正文，不重建整页
            previous != null -> previous.copy(
                items = list,
                // 分类行跟着最新一次 types 走：快照里可能还是老数据（或含二级分类）
                types = types,
                selectedTypeId = selectedTypeId,
                isSwitching = false,
                isRefreshing = false,
            )

            list.isEmpty() ->
                HomeUiState.Empty("该分类暂无内容", variant, columns, types, selectedTypeId, searchHot)

            else -> contentOf(list)
        }
    }

    /** 请求失败：骨架要退掉，否则用户停在骨架上看不到"失败了"。 */
    private fun applyListFailure(typeId: Int, message: String) {
        if (typeId != selectedTypeId) return
        _state.update { it.withItemsFailure(message) }
    }

    /** 用当前配置拼一个 Content：只有列表是变量，其余沿用已加载好的值。 */
    private fun contentOf(items: List<Vod>): HomeUiState.Content = HomeUiState.Content(
        items = items,
        variant = variant,
        columns = columns,
        types = types,
        selectedTypeId = selectedTypeId,
        banners = banners,
        blocks = blocks,
        links = links,
        modules = modules,
        continueWatching = continueWatching,
        featured = featured,
        hot = hot,
        guess = guess,
        topics = topics,
        lives = lives,
        pushCount = pushCount,
        pushEnabled = pushEnabled,
        searchHot = searchHot,
    )

    /**
     * 首屏铺好之后，后台把其余分类的列表都拉一遍。
     *
     * 不阻塞首屏：预热自己起 job，失败也不影响任何 UI（用户切过去时再按需请求一次）。
     */
    private fun prefetchTypes() {
        feedCache.prefetch(types.map { it.id }.filter { it != selectedTypeId })
    }

    /**
     * 分类页的热度榜/猜你喜欢。
     *
     * 后端按 `type_id` 返回该分类的配置，没单独配时回退全局（type_id=0）；
     * 客户端只按当前分类取一次，结果缓存，切走再切回不重复请求。
     * 请求期间又切了分类就丢弃，避免高亮与内容对不上。
     */
    private fun loadSpotlights(typeId: Int) {
        val cached = spotlightsByType[typeId]
        if (cached != null) {
            applySpotlights(typeId, cached)
            return
        }
        viewModelScope.launch {
            val hot = (repository.hotByType(typeId) as? AppResult.Success)?.value ?: HomeSpotlight()
            val guess = (repository.guessByType(typeId) as? AppResult.Success)?.value ?: HomeSpotlight()
            val pair = hot to guess
            spotlightsByType[typeId] = pair
            applySpotlights(typeId, pair)
        }
    }

    private fun applySpotlights(typeId: Int, pair: Pair<HomeSpotlight, HomeSpotlight>) {
        if (typeId != selectedTypeId) return
        (_state.value as? HomeUiState.Content)?.let { current ->
            _state.value = current.copy(hot = pair.first, guess = pair.second)
        }
    }

    companion object {

        fun factory(
            repository: VodRepository,
            startupRepository: StartupRepository,
            continueWatchingRepository: ContinueWatchingRepository,
            topicRepository: TopicRepository = EmptyTopicRepository,
            liveRepository: LiveRepository = EmptyLiveRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    repository = repository,
                    startupRepository = startupRepository,
                    continueWatchingRepository = continueWatchingRepository,
                    topicRepository = topicRepository,
                    liveRepository = liveRepository,
                )
            }
        }
    }
}

/** 没有专题数据源时的兜底：首页只少一块横滑，不影响正文。 */
private object EmptyTopicRepository : TopicRepository {

    override suspend fun list(): AppResult<List<Topic>> = AppResult.Success(emptyList())

    override suspend fun detail(topicId: String): AppResult<TopicDetail> =
        AppResult.Failure(AppError.Unknown(null))
}

/** 没有直播数据源时的兜底。 */
private object EmptyLiveRepository : LiveRepository {

    override suspend fun list(page: Int, limit: Int, group: String): AppResult<LivePage> =
        AppResult.Success(
            LivePage(items = emptyList(), total = 0, groups = emptyList(), hasMore = false),
        )
}

/** 后台模块顺序；缓存缺失或后台没配时退回全部模块。 */
private fun StartupConfig?.moduleOrder(): List<HomeModule> =
    this?.homeModules?.ifEmpty { HomeModule.entries.toList() } ?: HomeModule.entries.toList()

/**
 * 错误文案收敛在一处，避免每个页面各写一套。
 */
internal fun AppError.toMessage(): String = when (this) {
    is AppError.Network -> "网络连接失败，请检查网络后重试"
    is AppError.Http -> "服务异常（$code）"
    is AppError.Business -> message.ifBlank { "请求被拒绝" }
    is AppError.Serialization -> "数据解析失败"
    is AppError.Unknown -> "未知错误"
}

/**
 * 首帧状态：有快照就直接铺成 Content，没有才走 Loading 骨架。
 *
 * 放在类外是为了让 [HomeViewModel] 不超过 detekt 的类体积阈值；
 * 它不碰任何实例状态，只依赖快照与启动配置两份输入。
 */
private fun homeInitialState(snapshot: HomeSnapshotStore?, cachedStartup: StartupConfig?): HomeUiState {
    val cached = snapshot?.takeIf { it.hasContent() }
        ?: return HomeUiState.Loading(modules = cachedStartup.moduleOrder())
    return HomeUiState.Content(
        items = cached.items(),
        variant = cached.variant() ?: HomeVariant.CARD_GRID_C,
        columns = cached.columns().takeIf { it > 0 } ?: StartupConfig.DEFAULT_HOME_COLUMNS,
        types = cached.types(),
        selectedTypeId = 0,
        banners = cached.banners(),
        blocks = cached.blocks(),
        links = cached.links(),
        modules = cachedStartup.moduleOrder(),
        continueWatching = emptyList(),
        featured = cached.featured() ?: HomeSpotlight(),
        hot = cached.hot() ?: HomeSpotlight(),
        guess = cached.guess() ?: HomeSpotlight(),
        pushCount = 0,
        pushEnabled = cachedStartup?.pushEnabled ?: false,
        searchHot = cachedStartup?.searchHot ?: emptyList(),
    )
}

/**
 * 首页列表请求失败时的状态收敛。
 *
 * 快照已经铺出内容时不要整页换成错误页：网断了也应该继续看上次的首页，
 * 下拉刷新本身有 toast/进度条作为反馈。放在类外是为了压住 detekt 的类体积与函数长度。
 */
private fun HomeUiState.withItemsFailure(message: String): HomeUiState =
    if (this is HomeUiState.Content) copy(isRefreshing = false, isSwitching = false) else HomeUiState.Error(message)

/** 启动配置里影响首页布局的几项：收成一个对象，免得写回函数参数超限。 */
private data class HomeLayout(
    val variant: HomeVariant,
    val columns: Int,
    val modules: List<HomeModule>,
    val searchHot: List<String>,
    val pushEnabled: Boolean,
)

/**
 * 把启动配置写回当前状态。
 *
 * 只改配置、不动内容：网络回来的新列数 / 模块顺序要立刻体现在已经铺出来的首页上，
 * 否则界面会一直用快照里的旧配置，必须再冷启动一次（从快照重读）才生效。
 */
private fun HomeUiState.withLayout(layout: HomeLayout): HomeUiState = when (this) {
    is HomeUiState.Loading -> HomeUiState.Loading(searchHot = layout.searchHot, modules = layout.modules)
    is HomeUiState.Content -> copy(
        variant = layout.variant,
        columns = layout.columns,
        modules = layout.modules,
        searchHot = layout.searchHot,
        pushEnabled = layout.pushEnabled,
    )

    else -> this
}
