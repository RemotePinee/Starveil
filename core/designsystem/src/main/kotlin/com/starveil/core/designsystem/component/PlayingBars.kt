package com.starveil.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.designsystem.token.Spacing
import kotlin.math.abs

/** 一根音量柱的宽 / 柱间距 / 图标高度，都用 4dp 栅格内的令牌算出来。 */
private val BarWidth: Dp = Spacing.xxs

private val BarGap: Dp = Spacing.xxs / 2

private val BarHeight: Dp = Spacing.md

private const val BAR_COUNT = 3

/** 最低柱高占总高的比例：再低就看不见了。 */
private const val MIN_BAR_FRACTION = 0.35f

/**
 * 「正在播放」音量柱：三根竖条错峰起伏，替代之前整格呼吸的写法。
 *
 * 呼吸（整格 alpha 0.55↔1）会让人以为格子是"禁用/加载中"，而且格子多时整片在闪；
 * 优酷那种做法只让一个 8×12dp 的小图标动，格子的底色和文字保持稳定。
 */
@Composable
fun PlayingBars(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onPrimary,
    contentDescription: String? = null,
) {
    val transition = rememberInfiniteTransition(label = "playingBars")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = Motion.BreathMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "playingBarsPhase",
    )
    Canvas(
        modifier = modifier
            .size(
                width = BarWidth * BAR_COUNT + BarGap * (BAR_COUNT - 1),
                height = BarHeight,
            )
            .semantics { this.contentDescription = contentDescription.orEmpty() },
    ) {
        val barWidth = BarWidth.toPx()
        val gap = BarGap.toPx()
        val centerY = size.height / 2f
        repeat(BAR_COUNT) { index ->
            // 三角形波：每根柱子相位错开 1/3 周期，看起来像音量在跳
            val wave = (phase + index / BAR_COUNT.toFloat()) % 1f
            val fraction = MIN_BAR_FRACTION + (1f - MIN_BAR_FRACTION) * (1f - abs(wave * 2f - 1f))
            val barHeight = size.height * fraction
            drawRoundRect(
                color = color,
                topLeft = Offset(index * (barWidth + gap), centerY - barHeight / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}
