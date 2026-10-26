package com.starveil.feature.player

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.starveil.core.designsystem.token.Motion

/**
 * 播放器控制层：顶栏 + 底栏。
 *
 * 与最大的差别是控制层由 Compose 全部自绘，`PlayerView` 自带的控制器关掉了
 * （见 [PlayerScreen] 的 `useController = false`）：官方控制器是另一套尺寸与配色，
 * 和应用的暗色令牌放一起像两个应用拼起来。
 *
 * 弹幕设置面板不在这里画：它和选集/倍速一样走 [PlayerSheetHost] 的底部弹层，
 * 弹层是 Activity 级窗口，内嵌在 16:9 播放器里会被压成一条。
 */
@Composable
internal fun PlayerChromeOverlay(
    state: PlayerUiState,
    actions: PlayerActions,
    config: ChromeConfig,
    modifier: Modifier = Modifier,
) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    AnimatedVisibility(
        visible = config.visible,
        enter = fadeIn(Motion.short),
        exit = fadeOut(Motion.short),
        modifier = modifier,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            PlayerTopBar(
                title = state.episodeTitle.ifBlank { state.sourceName },
                subtitle = episodeSubtitleOf(state),
                actions = TopActions(
                    danmuEnabled = config.danmuEnabled,
                    onToggleDanmu = config.onToggleDanmu,
                ),
                onBack = { dispatcher?.onBackPressed() },
                // 横屏全屏才让 inset：转屏那几帧不让，按钮就不会先往下掉再弹回去
                applyInsets = config.immersive,
                modifier = Modifier.align(Alignment.TopStart),
            )
            PlayerBottomBar(
                state = state,
                actions = actions,
                callbacks = BottomBarCallbacks(
                    onInteraction = config.onInteract,
                    onOpenSheet = config.onOpenSheet,
                    onCycleSpeed = config.onCycleSpeed,
                ),
                applyInsets = config.immersive,
                danmuEnabled = config.danmuEnabled,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * 顶栏副标题：线路名。
 *
 * 集数已经在主标题里（后端给的集名就是"第 1 集"），副标题只补线路，避免上下两行
 * 重复同一个集数。
 */
internal fun episodeSubtitleOf(state: PlayerUiState): String {
    val title = state.episodeTitle.ifBlank { state.sourceName }
    val source = state.sourceName
    return if (source.isNotBlank() && source != title) source else ""
}
