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
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.TrySeeAccess

/**
 * 竖屏播放页入口：ViewModel + 单实例 [Media3Player] + 纯 UI 三者接线。
 *
 * 播放器与 prepare 会话归 ViewModel 所有，导航暂离不释放；切集只换媒体源。
 * 视频面也只有一个（[VideoStage]），不在 pager 页或 URL/加载条件里创建销毁；
 * 真实画面跟随所属页滑动，其余页在自己的当前源首帧前始终使用不透明遮挡。
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
) {
    val ownsPlayback = viewModel.ownsPlayback(playbackToken)
    val state = viewModel.state.collectAsStateWithLifecycle().value
    // 引擎创建（Media3 构造成本在低端机可达数百毫秒）放到首帧之后：
    // 进入剧场先立刻铺封面，引擎就绪再接管，页面不会比 dock 晚一拍出现。
    val playback = rememberVerticalPlayback(viewModel, playbackToken)
    if (playback == null) {
        VerticalBlurredBackdrop(state.cover, Modifier.fillMaxSize())
        return
    }
    val danmu = viewModel.danmu.collectAsStateWithLifecycle().value
    val engine = playback.engine
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
        onOpenSeries = { playback.openSeries(viewModel, options) },
        onToggleRecommendations = {
            showEpisodes = false
            showRecommendations = !showRecommendations
            if (showRecommendations) options.onRecommendationsOpen()
        },
        onSeek = viewModel::onSeekTo,
    )
    VerticalEngineEffects(playback, state, viewModel, options, speed, pagerState, playbackToken)
    VerticalPagerSync(state, pagerState, viewModel, playbackToken, options)
    VerticalPlayScreen(
        stage = VerticalPlayStage(
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
            hasCurrentFrame = playback.session.isPrepared(state) && engine.hasRenderedCurrentFrame,
            preparedMediaPage = playback.session.preparedMediaPage,
            hasPreparedFrame = engine.hasRenderedCurrentFrame,
        ),
        modifier = modifier,
        ownsPlayback = ownsPlayback,
    )
}

/**
 * 首次得权才获取引擎，且不在组合里同步构造：Media3 播放器创建要上百毫秒，
 * 放在首帧内会让整页比 dock 晚一拍出来。这里先返回 null，下一帧再交付实例。
 */
@Composable
private fun rememberVerticalPlayback(viewModel: VerticalPlayViewModel, token: Any): VerticalPlaybackOwner? {
    val context = LocalContext.current
    val holder = remember(viewModel, token) { mutableStateOf<VerticalPlaybackOwner?>(null) }
    LaunchedEffect(viewModel, token) {
        if (holder.value == null && viewModel.ownsPlayback(token)) {
            holder.value = viewModel.playback.get(context)
        }
    }
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
    val preparedMediaPage: Int? = null,
    /** 实际 prepared 源的帧，可随它自己的旧页退场，但不能露在请求的新页。 */
    val hasPreparedFrame: Boolean = false,
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
            .clipToBounds().background(starveilExtraColors.scrimStrong),
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
