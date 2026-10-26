package com.starveil.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Motion

/**
 * 骨架灰块：一块占位，尺寸由调用方用 `size` / `fillMaxWidth` / `aspectRatio` 给。
 *
 * 只做"一块会呼吸的底色"，不预设布局——骨架的形状由页面自己的真实布局决定，
 * 替各页写死一套卡片形状，改几次就和真实布局对不上了。
 * 首屏整屏加载仍用 [StarveilLoadingScreen]，那是一个转圈，不是骨架。
 */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, shape: Shape = AppShape.medium) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = SKELETON_ALPHA_MIN,
        targetValue = SKELETON_ALPHA_MAX,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = Motion.BreathMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton-alpha",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(starveilExtraColors.surfaceCard.copy(alpha = alpha)),
    )
}

/** 呼吸区间：再浅在暗色底上看不出来，再深就成了一块脏色。 */
private const val SKELETON_ALPHA_MIN = 0.35f
private const val SKELETON_ALPHA_MAX = 0.75f
