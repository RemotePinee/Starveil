package com.starveil.feature.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.designsystem.token.Spacing
import kotlinx.coroutines.launch

import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
/** 旧画面淡出：只要盖住"内容被换掉"那一下，再长就变成卡顿。 */
private const val FADE_OUT_MS = 90

/** 新画面淡入：比淡出长得多，落点用 emphasized，收得住不拖泥带水。 */
private const val FADE_IN_MS = 220

/** 新画面上浮的距离：一档小间距就够，读起来是浮上来而不是跳出来。 */
private val COVER_RISE = Spacing.sm

/**
 * 分类切换的淡入淡出（Material 的 fade through）。
 *
 * 首页正文是同一个 LazyColumn，切分类只是把列表内容换掉，直接换就是一次硬切：
 * 片名瞬间变、海报先空一帧再逐张填上去。这里先把整块正文淡出、换掉状态、再淡入，
 * 用户看到的是"旧的化掉、新的浮上来"，中间那一下替换藏在透明的 90ms 里。
 *
 * 三个取舍：
 * - 不用 `AnimatedContent` / `Crossfade`：它们会在过渡期间同时组合**两套 LazyColumn**，
 *   同一个 `LazyListState` 被两份列表抢着写，滚动位置和轮播视差都会跳。这里只有一份列表。
 * - 动画值只在绘制阶段读（`graphicsLayer`），不逐帧重组；[select] 是套过动画的选择动作。
 * - 连点两个 Tab：上一次 `animateTo` 被取消，协程在切换前就结束，只有最后一次生效。
 */
@Composable
internal fun rememberCategorySwitch(
    selectedTypeId: Int,
    onSelectType: (Int) -> Unit,
): CategorySwitch {
    val scope = rememberCoroutineScope()
    val fade = remember { Animatable(1f) }
    val risePx = with(LocalDensity.current) { COVER_RISE.toPx() }
    // 供挂起中的切换协程读最新值：remember 捕获的会是点击那一刻的旧值
    val currentTypeId by rememberUpdatedState(selectedTypeId)
    val currentOnSelect by rememberUpdatedState(onSelectType)
    val select = remember(scope) {
        { typeId: Int ->
            if (typeId != currentTypeId) {
                scope.launch {
                    fade.animateTo(0f, tween(FADE_OUT_MS, easing = Motion.standard))
                    currentOnSelect(typeId)
                    fade.animateTo(1f, tween(FADE_IN_MS, easing = Motion.emphasized))
                }
            }
        }
    }
    return remember(fade, risePx, select) { CategorySwitch(fade, risePx, select) }
}

/** 切分类时正文那一层要用的状态；[select] 是套过淡入淡出的选分类动作。 */
internal class CategorySwitch(
    private val fade: Animatable<Float, AnimationVector1D>,
    private val risePx: Float,
    val select: (Int) -> Unit,
) {

    /** 正文层的修饰符：alpha 与上浮都由切换动画派生，值在绘制阶段读，不触发重组。 */
    fun contentModifier(): Modifier = Modifier.graphicsLayer {
        alpha = fade.value
        translationY = (1f - fade.value) * risePx
    }
}

/** 左右横滑切分类：超过 [SWIPE_SLOP_MULTIPLIER] 个触摸斜率才认，避免竖着滚列表时误触。 */
internal fun Modifier.categorySwipe(
    categoryIds: List<Int>,
    selectedTypeId: Int,
    onSelect: (Int) -> Unit,
): Modifier = composed {
    val swipeDistancePx = LocalViewConfiguration.current.touchSlop * SWIPE_SLOP_MULTIPLIER
    pointerInput(categoryIds, selectedTypeId, swipeDistancePx) {
        var horizontalDragDistance = 0f
        detectHorizontalDragGestures(
            onHorizontalDrag = { change, dragAmount ->
                change.consume()
                horizontalDragDistance += dragAmount
            },
            onDragEnd = {
                val currentIndex = categoryIds.indexOf(selectedTypeId)
                if (currentIndex >= 0) {
                    val nextIndex = when {
                        horizontalDragDistance > swipeDistancePx -> currentIndex - 1
                        horizontalDragDistance < -swipeDistancePx -> currentIndex + 1
                        else -> currentIndex
                    }
                    categoryIds.getOrNull(nextIndex)?.let(onSelect)
                }
                horizontalDragDistance = 0f
            },
            onDragCancel = { horizontalDragDistance = 0f },
        )
    }
}

private const val SWIPE_SLOP_MULTIPLIER = 2.5f
