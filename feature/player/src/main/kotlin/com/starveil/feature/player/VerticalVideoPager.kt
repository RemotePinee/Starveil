package com.starveil.feature.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.withTimeoutOrNull

/** 只有绘制坐标随 pager 改变；包装层、视频尺寸与 TextureView 的组合身份始终稳定。 */
@Composable
internal fun VerticalVideoOutput(stage: VerticalPlayStage, showPauseHint: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize().graphicsLayer {
            val pager = stage.pagerState
            val layout = pager.layoutInfo
            translationY = verticalVideoTranslationY(
                mediaPage = stage.preparedMediaPage,
                currentPage = pager.currentPage,
                offsetFraction = pager.currentPageOffsetFraction,
                pageStridePx = layout.pageSize + layout.pageSpacing,
                preview = stage.options.preview,
            )
        },
    ) {
        VideoStage(engine = stage.engine)
        if (showPauseHint) PlaybackHint(stage)
    }
}

/** 暂停提示与单个视频输出共用 pager 平移，切集时随旧集离场而非悬在新集正中。 */
@Composable
private fun BoxScope.PlaybackHint(stage: VerticalPlayStage) {
    val state = stage.state
    val waiting = verticalShowsLoading(state, stage.options, stage.hasCurrentFrame)
    val paused = !state.isPlaying && !state.isBlocked
    val available = state.errorMessage == null && !waiting
    if (paused && available) {
        PauseHint(onPlayPause = stage.actions.onPlayPause, modifier = Modifier.align(Alignment.Center))
    }
}

/** 视频在下、逐页遮挡在上。预览不创建内层滚动容器，让纵向手势完全交给 feed。 */
@Composable
internal fun VerticalVideoPager(stage: VerticalPlayStage) {
    val gestures = verticalPlaybackGestures(stage)
    if (stage.options.preview) {
        Box(modifier = gestures) { EpisodeCover(stage, stage.state.currentIndex) }
    } else {
        VerticalPager(
            state = stage.pagerState,
            beyondViewportPageCount = 1,
            userScrollEnabled = !stage.options.transitioning && !stage.showEpisodes && !stage.showRecommendations,
            key = { page -> page },
            modifier = gestures,
        ) { page ->
            EpisodeCover(stage, page)
        }
    }
}

@Composable
private fun EpisodeCover(stage: VerticalPlayStage, page: Int) {
    if (page !in stage.state.episodes.indices) return
    VerticalEpisodePage(
        cover = stage.state.cover,
        showCover = !verticalPageShowsVideo(
            page = page,
            requestedPage = stage.state.currentIndex,
            framePage = stage.preparedMediaPage.takeIf { stage.hasPreparedFrame },
            hasCurrentFrame = stage.hasCurrentFrame,
        ),
    )
}

@Composable
private fun verticalPlaybackGestures(stage: VerticalPlayStage): Modifier {
    val actions = rememberUpdatedState(stage.actions)
    return Modifier.fillMaxSize()
        .semantics {
            onClick(label = if (stage.state.isPlaying) "暂停" else "播放") {
                actions.value.onPlayPause()
                true
            }
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                up.consume()
                actions.value.onPlayPause()
                // 单击抬手立即响应；第二次点击只并入同一次播放切换。
                val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                    awaitFirstDown(requireUnconsumed = false)
                } ?: return@awaitEachGesture
                second.consume()
                waitForUpOrCancellation()?.consume()
            }
        }
}
