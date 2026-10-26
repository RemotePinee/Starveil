package com.starveil.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowClockwise
import com.adamglin.phosphoricons.regular.WarningCircle
import com.starveil.core.designsystem.component.StarveilLoading
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.designsystem.token.Spacing
import kotlinx.coroutines.delay

internal fun verticalShowsLoading(
    state: VerticalPlayUiState,
    options: VerticalPlayOptions,
    hasCurrentFrame: Boolean,
): Boolean {
    if (state.isBlocked || state.errorMessage != null) return false
    return options.accessPending ||
        (options.active && verticalNeedsBuffering(state, hasCurrentFrame, active = true))
}

internal fun verticalLoadingPhase(
    state: VerticalPlayUiState,
    options: VerticalPlayOptions,
    preparedMediaPage: Int?,
    hasCurrentFrame: Boolean,
): VerticalLoadingPhase = when {
    state.isLoading -> VerticalLoadingPhase.Details
    options.accessPending -> VerticalLoadingPhase.Access
    preparedMediaPage != state.currentIndex -> VerticalLoadingPhase.Video
    !hasCurrentFrame -> VerticalLoadingPhase.Frame
    else -> VerticalLoadingPhase.Buffering
}

/** 实际状态像直播消息一样向上交替；只在标题上方显示，不遮挡视频中央。 */
@Composable
internal fun VerticalPlaybackStatus(stage: VerticalPlayStage) {
    val waiting = stage.state.isLoading || verticalShowsLoading(stage.state, stage.options, stage.hasCurrentFrame)
    val showReady = rememberLoadingCompletion(
        stage.state.mediaSource(), waiting, stage.options.active && stage.hasCurrentFrame,
    )
    val error = stage.state.errorMessage ?: MSG_NO_EPISODE.takeIf {
        !stage.state.isLoading && stage.state.episodes.isEmpty()
    }
    if (error != null) {
        PlaybackStatusError(error, stage.actions.onRetry)
    } else {
        AnimatedVisibility(
            visible = waiting || showReady,
            enter = fadeIn(tween(Motion.ShortMs)) + slideInVertically(tween(Motion.MediumMs)) { it },
            exit = fadeOut(tween(Motion.ShortMs)) + slideOutVertically(tween(Motion.MediumMs)) { -it },
        ) {
            val phase = if (waiting) {
                verticalLoadingPhase(stage.state, stage.options, stage.preparedMediaPage, stage.hasCurrentFrame)
            } else VerticalLoadingPhase.Ready
            VerticalLoadingStatus(phase)
        }
    }
}

/** 完成提示只响应真实首帧；计时仅用于收起消息，不用于推进加载阶段。 */
@Composable
private fun rememberLoadingCompletion(source: VerticalMediaSource?, waiting: Boolean, frameReady: Boolean): Boolean {
    var wasWaiting by remember(source) { mutableStateOf(false) }
    var showReady by remember(source) { mutableStateOf(false) }
    LaunchedEffect(source, waiting, frameReady) {
        if (waiting) {
            wasWaiting = true
            showReady = false
        } else if (wasWaiting && frameReady) {
            showReady = true
            delay(Motion.BreathMs.toLong())
            showReady = false
            wasWaiting = false
        } else {
            showReady = false
        }
    }
    return showReady
}

@Composable
internal fun VerticalLoadingStatus(phase: VerticalLoadingPhase, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .padding(vertical = Spacing.xxs)
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                stateDescription = phase.title
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (phase != VerticalLoadingPhase.Ready) {
            StarveilLoading(
                modifier = Modifier.size(IconSize.sm), indicatorSize = IconSize.sm, message = "",
            )
        }
        AnimatedContent(
            targetState = phase,
            transitionSpec = {
                (fadeIn(tween(Motion.MediumMs)) + slideInVertically(tween(Motion.MediumMs)) { it })
                    .togetherWith(
                        fadeOut(tween(Motion.ShortMs)) + slideOutVertically(tween(Motion.ShortMs)) { -it },
                    )
            },
            label = "vertical-loading-message",
        ) { current ->
            Text(
                current.title, style = MaterialTheme.typography.labelMedium, color = starveilExtraColors.onImage,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 失败与恢复操作合为一条轻量消息，不拆成散落的文字和按钮。 */
@Composable
private fun PlaybackStatusError(message: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm)
            .padding(end = Spacing.md, top = Spacing.xxs, bottom = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            PhosphorIcons.Regular.WarningCircle, contentDescription = null,
            tint = starveilExtraColors.onImage, modifier = Modifier.size(IconSize.sm),
        )
        // fill = false：短提示不吃满整行，重试按钮紧跟其后；长提示仍可按剩余宽度换行，
        // 按钮先被测量，不会被文字挤出容器。
        Text(
            message, style = MaterialTheme.typography.bodySmall, color = starveilExtraColors.onImage,
            modifier = Modifier.weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite },
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.height(Spacing.xl),
            shape = AppShape.full,
            contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.none),
            colors = ButtonDefaults.buttonColors(
                containerColor = starveilExtraColors.onImage,
                contentColor = starveilExtraColors.scrimStrong,
            ),
        ) {
            Icon(
                PhosphorIcons.Regular.ArrowClockwise,
                contentDescription = null,
                modifier = Modifier.size(IconSize.sm),
            )
            Text("重试", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = Spacing.xs))
        }
    }
}
