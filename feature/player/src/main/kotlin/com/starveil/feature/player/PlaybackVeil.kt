package com.starveil.feature.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.Spacing
import kotlin.math.abs

/** 扫光周期：慢一点更像"正在准备"，不像进度条在跑。 */
private const val SWEEP_MS = 2_600

/** 光带宽度占画面宽度比例。 */
private const val BAND_WIDTH_RATIO = 0.45f

/** 扫光亮度：极低，只在纯黑上浮一点点。 */
private const val GLOW_ALPHA = 0.05f

/** 三段指示的循环周期。 */
private const val SEGMENT_MS = 1_300

/** 段数。 */
private const val SEGMENT_COUNT = 3

private const val LABEL_LETTER_SPACING_SP = 1.5f

/** 单段宽度 / 高度。 */
private const val SEGMENT_WIDTH_DP = 22f

private const val SEGMENT_HEIGHT_DP = 3f

private const val SEGMENT_GAP_DP = 6f

/**
 * 播放加载态（解析线路 + 起播缓冲共用）。
 *
 * 视觉：纯黑**不透明**底层 + 极淡斜扫光 + 居中三段指示 + 一行小字。
 * 不用转圈、不用百分比：解析与缓冲是同一件事的上下半段，出现两套视觉会让人觉得卡了两次。
 * 遮罩保持不透明，海报/首帧不从上边透出来。
 *
 * 它消失的那一刻，画面已经在播。
 */
@Composable
internal fun PlaybackVeil(label: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "playback-veil")
    val sweep by transition.animateFloat(
        initialValue = -BAND_WIDTH_RATIO,
        targetValue = 1f + BAND_WIDTH_RATIO,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SWEEP_MS, easing = LinearEasing),
        ),
        label = "sweep",
    )
    val stage by transition.animateFloat(
        initialValue = 0f,
        targetValue = SEGMENT_COUNT.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SEGMENT_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "segments",
    )
    val glow = starveilExtraColors.onImage.copy(alpha = GLOW_ALPHA)
    Box(
        modifier = modifier
            .fillMaxSize()
            // 不透明底：解析时下面是海报、缓冲时下面是首帧，透出来就是脏
            .background(MaterialTheme.colorScheme.background)
            .drawBehind {
                val bandWidth = size.width * BAND_WIDTH_RATIO
                val startX = size.width * sweep
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, glow, Color.Transparent),
                        startX = startX,
                        endX = startX + bandWidth,
                    ),
                    topLeft = Offset.Zero,
                    size = Size(size.width, size.height),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Segments(stage = stage)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = LABEL_LETTER_SPACING_SP.sp,
                    fontWeight = FontWeight.Normal,
                ),
                color = starveilExtraColors.textMuted,
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }
    }
}

/** 三段指示：当前段满亮，其余按距离衰减，形成"往前走"的节奏而不是闪烁。 */
@Composable
private fun Segments(stage: Float) {
    val accent = MaterialTheme.colorScheme.onSurface
    val totalWidth = SEGMENT_WIDTH_DP * SEGMENT_COUNT + SEGMENT_GAP_DP * (SEGMENT_COUNT - 1)
    Canvas(modifier = Modifier.width(totalWidth.dp).height(SEGMENT_HEIGHT_DP.dp)) {
        val gap = SEGMENT_GAP_DP.dp.toPx()
        val barWidth = SEGMENT_WIDTH_DP.dp.toPx()
        repeat(SEGMENT_COUNT) { index ->
            // 当前段满亮，前后各衰减一档：读起来是"往前走"，不是闪烁
            val distance = abs(stage - index - 1f)
            val alpha = (1f - distance.coerceIn(0f, 1.5f) / 1.5f).coerceIn(0.18f, 1f)
            drawRoundRect(
                color = accent.copy(alpha = alpha * 0.9f),
                topLeft = Offset(index * (barWidth + gap), 0f),
                size = Size(barWidth, size.height),
                cornerRadius = CornerRadius(size.height / 2f),
            )
        }
    }
}
