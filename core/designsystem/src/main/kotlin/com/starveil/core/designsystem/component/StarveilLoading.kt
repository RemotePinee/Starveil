package com.starveil.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.Spacing

/**
 * 全局加载态：一枚主题色细环 + 一行浅色文案，居中。
 *
 * 为什么不用 `CircularProgressIndicator`：系统那个是满环匀速转，颗粒粗、颜色不跟主题，
 * 一屏只有它的时候很抢眼。这里用「透明 → 主色」的扫掠渐变只画 3/4 圈、圆头收尾，
 * 转起来看得出方向和"还在动"，又不会像实心圆环那样压住页面。
 *
 * 所有页面的首屏加载都用它，不再各写一套骨架屏——骨架要跟着真实布局改，改几次就对不上。
 */
@Composable
fun StarveilLoading(
    modifier: Modifier = Modifier,
    message: String = "加载中",
    indicatorSize: Dp = RING_SIZE,
) {
    val transition = rememberInfiniteTransition(label = "starveil-loading")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SWEEP_DURATION_MS, easing = LinearEasing),
        ),
        label = "starveil-loading-angle",
    )
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val primary = MaterialTheme.colorScheme.primary
        Canvas(
            modifier = Modifier
                .size(indicatorSize)
                .rotate(angle),
        ) {
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(
                        primary.copy(alpha = 0f),
                        primary.copy(alpha = 0.35f),
                        primary,
                    ),
                ),
                startAngle = 0f,
                sweepAngle = SWEEP_ANGLE,
                useCenter = false,
                style = Stroke(width = STROKE_WIDTH.toPx(), cap = StrokeCap.Round),
            )
        }
        if (message.isNotBlank()) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = starveilExtraColors.textMuted,
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }
    }
}

/** 铺满父容器的加载态：页面级首屏用它，省得每个调用点各写一遍居中容器。 */
@Composable
fun StarveilLoadingScreen(
    modifier: Modifier = Modifier,
    message: String = "加载中",
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StarveilLoading(message = message)
    }
}

/**
 * 环直径：`Spacing.xl + Spacing.xs`（24+4=28dp）——再大就变成视觉主体，再小看不出在转。
 */
private val RING_SIZE: Dp = Spacing.xl + Spacing.xs

/** 环粗：`hairline` 的 2.5 倍（2.5dp）；1.5~3dp 之间最像"细环"，超过 3dp 就变回实心圈。 */
private val STROKE_WIDTH: Dp = Spacing.hairline * 2.5f

/** 只画 3/4 圈：留缺口才看得出转动的方向。 */
private const val SWEEP_ANGLE = 270f

/** 一圈 900ms：比系统默认稍慢，读起来是"从容"而不是"急"。 */
private const val SWEEP_DURATION_MS = 900
