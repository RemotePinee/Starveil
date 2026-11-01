package com.starveil.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.TrySeeAccess

/**
 * 竖屏播放页入口：ViewModel + 共享 [ShortPlayFeedPlayback] + 纯 UI 三者接线。
 *
 * 播放器不归本页创建：feed 页由 `ShortPlayEntry` 注入整条信息流唯一的一台，目的地页
 * 自己 `remember` 一台（见 [rememberVerticalPlayback]）。换集只换媒体项，VM 只读进度。
 * 画面也归页面自己：归画面的那一页把视频画在自己的封面之上、入口条/右栏之下（见 `hostsVideo`），
 * 视频与页面是同一个布局节点，滑页不需要第二处坐标来对齐。
 */
@Composable
fun VerticalPlayRoute(
    viewModel: VerticalPlayViewModel,
    onBack: () -> Unit,
    onUpgrade: (TrySeeAccess, Int) -> Unit,
    /** 约束前景安全区；视频与 pager 不参与 dock 高度动画。 */
    modifier: Modifier = Modifier,
    /** 试看出口能力位；会员关闭时 `canUpgrade=0`，浮层只剩积分按钮。 */
    unlock: UnlockOptions = UnlockOptions(),
    /** 看完激励广告后的回调：本模块只上抛点击，换签与重查闸门归 `:app`。 */
    onWatchAd: () -> Unit = {},
    options: VerticalPlayOptions = VerticalPlayOptions(),
    playbackToken: Any = viewModel.defaultPlaybackToken,
    /** feed 级共享播放器；null 表示本页自持（目的地页）。 */
    feedPlayback: ShortPlayFeedPlayback? = null,
    /**
     * 本页是否画视频。feed 里只有画面归属的那一页为真（见 `rememberFeedVideoAnchor`），
     * 其余页只铺自己的封面；目的地页恒为真。
     */
    hostsVideo: Boolean = true,
) {
    val ownsPlayback = viewModel.ownsPlayback(playbackToken)
    val state = viewModel.state.collectAsStateWithLifecycle().value
    // 引擎创建（Media3 构造成本在低端机可达数百毫秒）放到首帧之后：进入剧场先立刻铺封面，
    // 引擎就绪再接管，页面不会比 dock 晚一拍出现。feed 页不建自己的，整条流共用外层那一台。
    val playback = rememberPlaybackSlot(feedPlayback, viewModel, playbackToken)
    if (playback == null) {
        VerticalBlurredBackdrop(state.cover, Modifier.fillMaxSize())
        return
    }
    // VM 只读进度：播放器由组合层接进来，VM 自己不创建也不释放。
    val owner = playback.owner
    SideEffect { viewModel.attachPlayback(owner) }
    DisposableEffect(owner) { onDispose { viewModel.detachPlayback(owner) } }
    val danmu = viewModel.danmu.collectAsStateWithLifecycle().value
    val engine = owner.engine
    // 整页只有一个 VM，换剧是 `load(vodId)` 原地换数据。pager 必须复位，但**不能**用
    // `key(state.vodId)` 包住：PagerState 一换，pager 下所有页与排在它后面的入口条都换
    // 组合身份，换剧那一帧整页重排——正是"全 52 集变全 60 集"时的闪黑。
    // 现在 PagerState 常驻，换剧只做一次非动画 scrollToPage（见 [DramaPagerReset]）。
    val pagerState = rememberPagerState(initialPage = state.currentIndex) {
        state.episodes.size.coerceAtLeast(1)
    }
    DramaPagerReset(vodId = state.vodId, page = state.currentIndex, pagerState = pagerState)
    var showEpisodes by rememberSaveable { mutableStateOf(false) }
    var showRecommendations by rememberSaveable { mutableStateOf(false) }
    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    BackHandler(enabled = ownsPlayback && (showEpisodes || showRecommendations)) {
        showEpisodes = false
        showRecommendations = false
    }
    val actions = VerticalPlayActions(
        onBack = onBack,
        onPlayPause = viewModel::onPlayPauseClick,
        onToggleEpisodes = {
            showRecommendations = false
            showEpisodes = !showEpisodes
            if (showEpisodes) options.onRecommendationsOpen()
        },
        onEpisodeSelect = { index ->
            showEpisodes = false
            if (options.preview) {
                state.episodes.getOrNull(index)?.let { options.onOpenSeries(it.number, 0L) }
            } else {
                viewModel.onPageChanged(index)
            }
        },
        onUpgrade = onUpgrade,
        onRetry = viewModel::retry,
        onWatchAd = onWatchAd,
        onSpeedSelect = { speed = it },
        onAction = options.onAction,
        onOpenSeries = { owner.openSeries(viewModel, options) },
        onToggleRecommendations = {
            showEpisodes = false
            showRecommendations = !showRecommendations
            if (showRecommendations) options.onRecommendationsOpen()
        },
        onSeek = viewModel::onSeekTo,
    )
    VerticalEngineEffects(owner, state, viewModel, options, speed, pagerState, playbackToken)
    VerticalPagerSync(state, pagerState, viewModel, playbackToken, options)
    val prepared = owner.session.isPrepared(state)
    val rendered = prepared && engine.hasRenderedCurrentFrame
    // 首帧回调只说明"已经渲染进输出"，撤遮挡要等它真的上屏；背景换深色跟它同一帧。
    val revealed = rememberRevealedFrame(rendered)
    val stage = VerticalPlayStage(
        state = state,
        pagerState = pagerState,
        actions = actions,
        engine = engine,
        showEpisodes = showEpisodes,
        danmu = danmu,
        unlock = unlock,
        speed = speed,
        options = options,
        showRecommendations = showRecommendations,
        hasCurrentFrame = rendered,
        hasRevealedFrame = revealed,
        // 共享引擎只认自己的 prepare 身份：不匹配时既不算"有帧"，也不参与平移。
        preparedMediaPage = owner.session.preparedMediaPage.takeIf { prepared },
        hasPreparedFrame = rendered,
        hostsVideo = hostsVideo,
    )
    VerticalPlayScreen(stage = stage, modifier = modifier, ownsPlayback = ownsPlayback)
}

/**
 * 换剧复位 pager：只改滚动位置，不重建组合。
 *
 * 只跟 [vodId]：`load()` 换剧时把 currentIndex 与 episodes 同一次推送出去，
 * 所以这里读到的就是新剧的起点。同一部剧详情回来导致的集数变化不归这里管，
 * 交给 `VerticalPagerSync`（它按集数重启），两条路径不抢同一个滚动。
 */
@Composable
private fun DramaPagerReset(vodId: Int, page: Int, pagerState: PagerState) {
    LaunchedEffect(vodId) {
        val target = page.coerceAtLeast(0)
        if (pagerState.currentPage == target && pagerState.currentPageOffsetFraction == 0f) return@LaunchedEffect
        pagerState.scrollToPage(target)
    }
}

/**
 * 首次得权才获取引擎，且不在组合里同步构造：Media3 播放器创建要上百毫秒，
 * 放在首帧内会让整页比 dock 晚一拍出来。这里先返回 null，下一帧再交付实例。
 *
 * 只有目的地页走这条：它自己就是整个播放面。feed 页由 `ShortPlayEntry` 注入同一台。
 */
@Composable
private fun rememberPlaybackSlot(
    shared: ShortPlayFeedPlayback?,
    viewModel: VerticalPlayViewModel,
    token: Any,
): ShortPlayFeedPlayback? {
    if (shared != null) return shared
    return rememberVerticalPlayback(viewModel, token)
}

@Composable
private fun rememberVerticalPlayback(viewModel: VerticalPlayViewModel, token: Any): ShortPlayFeedPlayback? {
    val context = LocalContext.current.applicationContext
    val holder = remember(viewModel, token) { mutableStateOf<ShortPlayFeedPlayback?>(null) }
    LaunchedEffect(viewModel, token) {
        if (holder.value == null && viewModel.ownsPlayback(token)) {
            holder.value = ShortPlayFeedPlayback(context)
        }
    }
    // 谁创建谁释放：feed 那台由 ShortPlayEntry 的 DisposableEffect 收。
    DisposableEffect(viewModel, token) { onDispose { holder.value?.release() } }
    // 不要因为这一刻"没有播放权"就交回 null：转场/失焦那一帧交回 null 会让整页
    // 退化成封面，贴底那组（标题 / 右栏 / 观看完整短剧条）瞬间消失，看着就是"跳走了"。
    return holder.value
}

private fun VerticalPlaybackOwner.openSeries(viewModel: VerticalPlayViewModel, options: VerticalPlayOptions) {
    val current = viewModel.state.value
    val position = if (session.isCurrent(current)) engine.currentPositionMs else current.positionMs
    options.onOpenSeries(current.current?.number ?: FIRST_EPISODE_NUMBER, position)
}

/**
 * 竖屏页的渲染输入。
 *
 * 收成一个对象而不是散成 5 个参数：detekt `LongParameterList` 的阈值是 5 个非默认参数，
 * 而 Compose 组件不允许用 `@Suppress` 绕过（`AGENTS.md` 第 2 节）。
 */
data class VerticalPlayStage(
    val state: VerticalPlayUiState,
    val pagerState: PagerState,
    val actions: VerticalPlayActions,
    val engine: Media3Player,
    /** 当前窗口内要上屏的弹幕；短剧默认关闭，恒为空。 */
    val showEpisodes: Boolean,
    val danmu: List<DanmuItem>,
    /** 试看出口能力位，由 `:app` 从服务端 `checkVodTrySee` 传入。 */
    val unlock: UnlockOptions = UnlockOptions(),
    /** 当前倍速：右栏的倍速按钮直接显示这一档。 */
    val speed: Float = 1f,
    val options: VerticalPlayOptions = VerticalPlayOptions(),
    val showRecommendations: Boolean = false,
    /** 请求集的 source/revision 与当前输出首帧均匹配，不能把旧集帧称为新集起播。 */
    val hasCurrentFrame: Boolean = false,
    /** 遮挡层可撤的时机：比 [hasCurrentFrame] 晚一档；背景换深色与遮挡撤除共用它。 */
    val hasRevealedFrame: Boolean = false,
    val preparedMediaPage: Int? = null,
    /** 实际 prepared 源的帧，可随它自己的旧页退场，但不能露在请求的新页。 */
    val hasPreparedFrame: Boolean = false,
    /** 视频面是否归本页画：归本页时视频在本页封面之上、入口条与右栏之下。 */
    val hostsVideo: Boolean = true,
)

/** 视频保留完整 viewport；调用方的 modifier 只让前景避开实测底栏与系统栏。 */
@Composable
internal fun VerticalPlayScreen(
    stage: VerticalPlayStage,
    modifier: Modifier = Modifier,
    ownsPlayback: Boolean = true,
) {
    VerticalStageContent(stage, modifier, ownsPlayback)
    if (ownsPlayback && stage.showEpisodes) VerticalEpisodeSheet(stage)
    if (ownsPlayback && stage.showRecommendations) VerticalRecommendationSheet(stage)
}

@Composable
private fun VerticalStageContent(stage: VerticalPlayStage, contentModifier: Modifier, ownsPlayback: Boolean) {
    val state = stage.state
    val showsPages = !state.isLoading && state.episodes.isNotEmpty()
    // 画画面 = 画面归本页 + 本页有播放权。收成一个布尔：视频底与视频面同进同退。
    val drawsVideo = stage.hostsVideo && ownsPlayback
    Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
        // 画面归本页时才铺视频底；其余页这一层让开，露出 feed 给这一页铺的模糊封面——
        // 页面因此永远不透明，滑页中没有一帧能露到 feed 的根容器底色。
        // 视频按 Fit 居中、一帧不裁，上下的空档由这张底填；换深色用延迟后的 hasRevealedFrame，
        // 与遮挡撤除同一帧，否则会"背景先黑、画面还盖着封面"。
        VerticalPlaybackBackdrop(
            cover = state.cover,
            revealed = stage.hasRevealedFrame,
            modifier = Modifier.visibleAlpha(drawsVideo || !showsPages),
        )
        // 常驻输出先绘制，逐页遮挡随后绘制；手势、URL、mode 都不改变输出组合身份。
        VerticalVideoOutput(stage, showPauseHint = ownsPlayback && showsPages, drawsVideo = drawsVideo)
        // 旧输出只读保留到旧目的地退场，不再接手势、面板或任何播放指令。
        if (!ownsPlayback) return@Box
        // 选集 pager 与试看层改成常驻调用、内部早退：以前是 `if (showsPages)` 条件组合，
        // 详情回来的那一帧要在 Box 里插入两个节点，排在后面的入口条整组换组合身份、
        // 重新组合一次——"全 N 集"文字跳变和闪黑落在同一帧，就是这条结构变化造成的。
        VerticalEpisodeLayer(stage, showsPages)
        // 入口条是浮在整屏之上的 overlay（见 PlaybackChrome），页面底部不再垫实心色带：
        // 滑页时入口条与它下面那条边同属一页，露出的永远是同一部剧的模糊封面。
        Box(contentModifier.fillMaxSize()) { OverlayLayer(stage) }
        PlaybackAccessLayer(stage)
    }
}

/** 选集 pager 与弹幕层。条件收在函数内部：调用点的组合结构不随详情到达而变。 */
@Composable
private fun VerticalEpisodeLayer(stage: VerticalPlayStage, showsPages: Boolean) {
    if (!showsPages) return
    VerticalVideoPager(stage)
    DanmuOverlay(items = stage.danmu, isPlaying = stage.state.isPlaying && stage.options.active)
}

/** 弹幕层。短剧默认没有弹幕来源，列表为空时 DanmuLayer 自己不画、也不驱动逐帧刷新。 */
@Composable
private fun DanmuOverlay(items: List<DanmuItem>, isPlaying: Boolean) {
    if (items.isEmpty()) return
    DanmuLayer(items = items, isPlaying = isPlaying, modifier = Modifier.fillMaxSize())
}

/** 预览控件让出外层 status bar 与推荐栏；视频本身仍可延伸到全屏。 */
@Composable
private fun OverlayLayer(stage: VerticalPlayStage) {
    val reservedTop = if (stage.options.preview) {
        Modifier.statusBarsPadding().padding(top = Spacing.rowHeight)
    } else {
        Modifier
    }
    Box(modifier = Modifier.fillMaxSize().then(reservedTop)) { PlaybackChrome(stage) }
}

@Composable
private fun BoxScope.PlaybackChrome(stage: VerticalPlayStage) {
    VerticalBottomChrome(stage)
    val expansion = stage.options.seriesExpansion.coerceIn(0f, 1f)
    if (expansion > 0f) {
        val enabled = !stage.options.preview && !stage.options.transitioning
        EpisodeTopBar(
            episodeNumber = stage.state.current?.number ?: FIRST_EPISODE_NUMBER,
            onBack = { if (enabled) stage.actions.onBack() },
            modifier = Modifier.align(Alignment.TopStart)
                .graphicsLayer {
                    alpha = expansion
                    translationY = -size.height * (1f - expansion)
                }
                .then(if (enabled) Modifier else Modifier.clearAndSetSemantics {}),
        )
    }
}

/** 试看结束/未解锁时压在提示之上；是否拦截完全由 state 表达。 */
@Composable
private fun BoxScope.PlaybackAccessLayer(stage: VerticalPlayStage) {
    val state = stage.state
    if (!state.isBlocked) return
    TrySeeOverlay(
        access = state.blockedAccess,
        episodeNumber = state.current?.number ?: FIRST_EPISODE_NUMBER,
        actions = stage.actions,
        unlock = stage.unlock,
        preview = stage.options.preview,
    )
}
