package com.starveil.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.TrySeeAccess

/**
 * 竖屏播放页入口：ViewModel + 共享 [ShortPlayFeedPlayback] + 纯 UI 三者接线。
 *
 * 播放器不归本页创建：feed 页由 `ShortPlayEntry` 注入整条信息流唯一的一台，目的地页
 * 自己 `remember` 一台（见 [rememberVerticalPlayback]）。换集只换媒体项，VM 只读进度。
 * 视频面在 feed 里也只有一份（[ShortPlayVideoHost.FEED]），页面只画覆盖层。
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
    /** 视频面归谁画。feed 页用 [ShortPlayVideoHost.FEED]，画面由 pager 下面那一份负责。 */
    videoHost: ShortPlayVideoHost = ShortPlayVideoHost.PAGE,
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
    val pagerState = rememberPagerState(initialPage = state.currentIndex) {
        state.episodes.size.coerceAtLeast(1)
    }
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
        // 共享引擎只认自己的 prepare 身份：不匹配时既不算"有帧"，也不参与平移。
        preparedMediaPage = owner.session.preparedMediaPage.takeIf { prepared },
        hasPreparedFrame = rendered,
        hostsVideo = videoHost == ShortPlayVideoHost.PAGE,
    )
    FeedStagePublisher(playback, stage, publishing = videoHost == ShortPlayVideoHost.FEED, active = options.active)
    VerticalPlayScreen(stage = stage, modifier = modifier, ownsPlayback = ownsPlayback)
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

/** 把本页的渲染输入交给 feed 级唯一输出；不是落页就不占槽位。 */
@Composable
private fun FeedStagePublisher(
    playback: ShortPlayFeedPlayback,
    stage: VerticalPlayStage,
    publishing: Boolean,
    active: Boolean,
) {
    val token = remember(playback) { Any() }
    SideEffect {
        if (publishing && active) playback.publish(token, stage) else playback.unpublish(token)
    }
    DisposableEffect(playback, token) { onDispose { playback.unpublish(token) } }
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
    val preparedMediaPage: Int? = null,
    /** 实际 prepared 源的帧，可随它自己的旧页退场，但不能露在请求的新页。 */
    val hasPreparedFrame: Boolean = false,
    /** 视频面是否归本页画：feed 页由 pager 下面那一份负责，页面只画覆盖层。 */
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
    Box(
        modifier = Modifier.fillMaxSize()
            .clipToBounds().background(stageBackdrop(stage.hostsVideo)),
    ) {
        // 常驻输出先绘制，逐页遮挡随后绘制；手势、URL、mode 都不改变输出组合身份。
        VerticalVideoOutput(stage, showPauseHint = ownsPlayback && !state.isLoading && state.episodes.isNotEmpty())
        // 旧输出只读保留到旧目的地退场，不再接手势、面板或任何播放指令。
        if (!ownsPlayback) return@Box
        if (state.isLoading || state.episodes.isEmpty()) {
            VerticalBlurredBackdrop(state.cover)
        } else {
            VerticalVideoPager(stage)
            DanmuOverlay(items = stage.danmu, isPlaying = state.isPlaying && stage.options.active)
        }
        Box(contentModifier.fillMaxSize()) { OverlayLayer(stage) }
        if (!state.isLoading && state.episodes.isNotEmpty()) {
            PlaybackAccessLayer(stage)
        }
    }
}

/** 视频面归 feed 时这里不能再铺不透明底：整页会盖住 pager 下面唯一的那块输出。 */
@Composable
private fun stageBackdrop(hostsVideo: Boolean): Color =
    if (hostsVideo) starveilExtraColors.scrimStrong else Color.Transparent

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
