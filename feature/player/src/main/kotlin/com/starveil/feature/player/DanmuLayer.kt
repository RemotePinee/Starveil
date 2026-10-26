package com.starveil.feature.player

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.Spacing
import kotlin.math.abs

/**
 * 横向速度：优酷 `getUniformSpeed()` 默认 0.275，单位是"屏宽/秒"
 * （`j.f1.a1.e.k.i.a` 反编译结果），即穿屏约 3.6 秒。
 */
private const val SPEED_SCREEN_PER_SECOND = 0.275f

/** 一条弹幕横穿整屏的耗时（秒），由屏宽速度取倒数。 */
private const val TRAVEL_SECONDS = 1f / SPEED_SCREEN_PER_SECOND

/**
 * 行高与行间距：优酷 `lineHeight = 24 × density`、`lineSpace = lineHeight + 6 × density`，
 * y 直接取 `行号 × 行间距`，第 0 行就贴画面顶部。
 */
private val LINE_HEIGHT_DP = 24.dp

private val LINE_SPACE_DP = 6.dp

/**
 * 同轨最小间隙（像素）：优酷用固定 40px 判断
 * （`b()` 里 `上一条右边缘 + 40.0f > 屏宽` 即换行），不按 dp 缩放。
 */
private const val LANE_EDGE_BUFFER_PX = 40f

/**
 * 每轨速度系数（优酷 `uniformSpeed × random(0.85~1.15)` 的确定性版本）。
 *
 * 同轨复用同一系数，避免同轨内追尾；跨轨不同系数让同一时刻的多条弹幕自然错开。
 */
private val LANE_SPEED_FACTORS = floatArrayOf(1f, 0.88f, 1.12f, 0.94f, 1.06f, 0.85f)

private fun laneSpeedFactor(lane: Int): Float =
    LANE_SPEED_FACTORS[Math.floorMod(lane, LANE_SPEED_FACTORS.size)]

private const val NANOS_PER_MS = 1_000_000L

/** 播放位置与插值时钟漂移超过它重新锚定（拖进度、暂停后恢复）。 */
private const val RESYNC_THRESHOLD_MS = 400L

/** 行数硬上限。 */
internal const val DANMU_MAX_LANES = 6

private const val MIN_LANES = 1

/** 默认 3 行。 */
internal const val DANMU_DEFAULT_LANES = 3

/** 默认占画面上半部分。 */
internal const val DANMU_DEFAULT_AREA = 0.45f

/** 顶部起点：优酷第 0 行 y=0，弹幕直接贴画面顶部。 */
private val TOP_INSET_DP = 0.dp

/** 绘制几何：行数、字号、区域比例、横向速度、顶部边距。 */
private class DanmuMotionGeometry(
    val lanes: Int,
    val textSizePx: Float,
    val speedPxPerSecond: Float,
    val window: DanmuLayoutWindow,
)

/** 可用区域：顶部让出控制栏，下方按用户选的显示区域折算。 */
private class DanmuLayoutWindow(
    val areaFraction: Float,
    val topInsetPx: Float,
    /** 行距（像素）：优酷 lineHeight 24dp + lineSpace 6dp。 */
    val laneStepPx: Float,
)

/** 一屏排布结果。 */
private class DanmuScene(
    val items: List<LaidDanmu>,
    val travelMs: Long,
    val motion: DanmuMotionGeometry,
)

/** 一帧的绘制参数（4 个：时间窗再收成对象）。 */
private class LaidFrame(
    val items: List<LaidDanmu>,
    val window: DanmuFrameWindow,
    val motion: DanmuMotionGeometry,
    val palette: DanmuPalette,
)

/** 当前帧的播放位置与可绘制下标区间。 */
private class DanmuFrameWindow(
    val positionMs: Long,
    val range: IntRange,
)

private class DanmuPalette(
    val table: Map<String, Int>,
    val fallback: Int,
)

private class DanmuMetrics(
    val textSizePx: Float,
    val gapPx: Float,
)

private class DanmuSceneOptions(
    val laneCount: Int,
    val areaFraction: Float,
    val topInsetPx: Float,
    val laneStepPx: Float,
)

private class DanmuRenderSpec(
    val metrics: DanmuMetrics,
    val options: DanmuSceneOptions,
)

/**
 * 自绘弹幕层。
 *
 * - [layoutDanmu] 决定哪条弹幕进哪条轨道（按真实文字宽度算占用时长）；
 * - 本层按播放位置算横坐标、按用户选的行数分行、每条用各自颜色画。
 *
 * 行数严格等于用户选择；行距在**绘制阶段**按真实画布高度算，避免起播第一帧
 * 约束未落定时算出错误行距（表现为"刚播少一行，拖进度后才正常"）。
 */
@Composable
fun DanmuLayer(
    items: List<DanmuItem>,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    fontScale: Float = 1f,
    positionMs: Long = 0L,
    laneCount: Int = DANMU_DEFAULT_LANES,
    opacity: Float = 1f,
    areaFraction: Float = DANMU_DEFAULT_AREA,
) {
    if (items.isEmpty()) return
    val defaultColor = starveilExtraColors.danmuText.toArgb()
    val textStyle = MaterialTheme.typography.bodyMedium
    val density = LocalDensity.current
    val textSizePx = with(density) { textStyle.fontSize.toPx() } * fontScale
    val gapPx = LANE_EDGE_BUFFER_PX
    val laneStepPx = with(density) { (LINE_HEIGHT_DP + LINE_SPACE_DP).toPx() }
    val topInsetPx = with(density) { TOP_INSET_DP.toPx() }

    // 透明度单独记：drawDanmu 每条要设 paint.color（ARGB），会把 alpha 覆盖回 255
    val textAlpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
    val paint = remember(defaultColor, textSizePx, textAlpha) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = defaultColor
            textSize = textSizePx
            typeface = Typeface.DEFAULT_BOLD
            alpha = textAlpha
        }
    }
    val colorTable = remember(items, defaultColor) { buildColorTable(items, defaultColor) }
    val framePosition = rememberInterpolatedPosition(positionMs = positionMs, isPlaying = isPlaying)

    BoxWithConstraints(modifier = modifier) {
        val scene = rememberDanmuScene(
            items = items,
            sizePx = constraints,
            paint = paint,
            spec = DanmuRenderSpec(
                metrics = DanmuMetrics(textSizePx = textSizePx, gapPx = gapPx),
                options = DanmuSceneOptions(
                    laneCount = laneCount,
                    areaFraction = areaFraction,
                    topInsetPx = topInsetPx,
                    laneStepPx = laneStepPx,
                ),
            ),
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 位置在绘制阶段读：每帧只重绘这一层，不触发重组
            val pos = framePosition.longValue
            drawDanmu(
                frame = LaidFrame(
                    items = scene.items,
                    window = DanmuFrameWindow(
                        positionMs = pos,
                        range = visibleRange(scene.items, pos, scene.travelMs),
                    ),
                    motion = scene.motion,
                    palette = DanmuPalette(table = colorTable, fallback = defaultColor),
                ),
                paint = paint,
                textAlpha = textAlpha,
            )
        }
    }
}

/** 与播放位置无关的排布：只在尺寸 / 字号 / 内容变化时重算。 */
@Composable
private fun rememberDanmuScene(
    items: List<DanmuItem>,
    sizePx: Constraints,
    paint: Paint,
    spec: DanmuRenderSpec,
): DanmuScene {
    val widthPx = sizePx.maxWidth.toFloat()
    val speedPxPerSecond = if (widthPx > 0f) widthPx / TRAVEL_SECONDS else 0f
    val lanes = spec.options.laneCount.coerceIn(MIN_LANES, DANMU_MAX_LANES)
    val motion = DanmuMotionGeometry(
        lanes = lanes,
        textSizePx = spec.metrics.textSizePx,
        speedPxPerSecond = speedPxPerSecond,
            window = DanmuLayoutWindow(
                areaFraction = spec.options.areaFraction,
                topInsetPx = spec.options.topInsetPx,
                laneStepPx = spec.options.laneStepPx,
            ),
    )
    return remember(items, widthPx, spec.metrics.textSizePx, spec.metrics.gapPx, lanes, speedPxPerSecond) {
        val laid = layoutDanmu(
            items = items,
            config = DanmuLayoutConfig(
                laneCount = lanes,
                speedPxPerSec = speedPxPerSecond,
                gapPx = spec.metrics.gapPx,
                measureWidth = { text -> paint.measureText(text) },
            ),
        )
        // 退出窗口要把整条文本走完算进去，否则长弹幕会在左边缘被提前移除
        val widestText = laid.maxOfOrNull { paint.measureText(it.text) } ?: 0f
        val travelMs = if (speedPxPerSecond > 0f) {
            ((widthPx + widestText) / speedPxPerSecond * 1_000f).toLong()
        } else {
            (TRAVEL_SECONDS * 1_000).toLong()
        }
        DanmuScene(items = laid, travelMs = travelMs, motion = motion)
    }
}

/** 画一帧。 */
private fun DrawScope.drawDanmu(frame: LaidFrame, paint: Paint, textAlpha: Int) {
    if (frame.window.range.isEmpty() || frame.motion.speedPxPerSecond <= 0f) return
    frame.window.range.forEach { index ->
        drawOne(frame, index, paint, textAlpha)
    }
    // 还原，避免同一支 Paint 把上一条颜色带给下一条
    paint.color = frame.palette.fallback
    paint.alpha = textAlpha
}

/** 单条：算入场横坐标，仍在屏内才画。 */
private fun DrawScope.drawOne(
    frame: LaidFrame,
    index: Int,
    paint: Paint,
    textAlpha: Int,
) {
    val item = frame.items[index]
    val age = frame.window.positionMs - item.atTimeMs
    // 每轨速度带 ±15% 抖动（优酷 uniformSpeed × random(0.85~1.15)，同轨复用同一速度）：
    // 同一秒涌入的多条弹幕不会一直并排成"柱"，会自然错开
    val laneSpeed = frame.motion.speedPxPerSecond * laneSpeedFactor(item.lane)
    val x = size.width - age / 1_000f * laneSpeed
    val textWidth = paint.measureText(item.text)
    if (x > size.width || x + textWidth < 0f) return
    val baseline = frame.motion.window.topInsetPx + frame.motion.textSizePx +
        frame.motion.window.laneStepPx * item.lane
    paint.color = item.colorHex?.let { frame.palette.table[it] } ?: frame.palette.fallback
    paint.alpha = textAlpha
    drawContext.canvas.nativeCanvas.drawText(item.text, x, baseline, paint)
}


/** 颜色表：带不带井号都认，解析失败回落默认白。 */
private fun buildColorTable(items: List<DanmuItem>, defaultColor: Int): Map<String, Int> {
    val table = HashMap<String, Int>()
    items.forEach { item ->
        val hex = item.colorHex?.trim().orEmpty()
        if (hex.isEmpty() || hex in table) return@forEach
        table[hex] = parseColorOrNull(hex) ?: defaultColor
    }
    return table
}

private fun parseColorOrNull(hex: String): Int? = runCatching {
    android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")
}.getOrNull()

/**
 * 播放位置的逐帧插值。
 *
 * 返回可变状态，由绘制层在 draw 阶段读取：每帧只重绘弹幕层，不重组。
 */
@Composable
private fun rememberInterpolatedPosition(positionMs: Long, isPlaying: Boolean): MutableLongState {
    val framePosition = remember { mutableLongStateOf(positionMs) }
    val anchorPosition = remember { mutableLongStateOf(positionMs) }
    val anchorNanos = remember { mutableLongStateOf(0L) }
    LaunchedEffect(positionMs) {
        if (abs(positionMs - framePosition.longValue) > RESYNC_THRESHOLD_MS) {
            anchorPosition.longValue = positionMs
            framePosition.longValue = positionMs
            anchorNanos.longValue = 0L
        }
    }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        if (anchorNanos.longValue == 0L) anchorPosition.longValue = positionMs
        val startNs = withFrameNanos { it }
        if (anchorNanos.longValue == 0L) anchorNanos.longValue = startNs
        val basePosition = anchorPosition.longValue
        val baseNanos = anchorNanos.longValue
        while (true) {
            withFrameNanos { now ->
                framePosition.longValue = basePosition + (now - baseNanos) / NANOS_PER_MS
            }
        }
    }
    return framePosition
}
