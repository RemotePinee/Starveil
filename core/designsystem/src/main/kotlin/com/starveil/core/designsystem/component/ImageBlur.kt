package com.starveil.core.designsystem.component

import android.content.Context
import android.graphics.Bitmap
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Scale
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/** 采样长边的像素上限：模糊底图与屏幕分辨率解耦，128 已足够铺满全屏。 */
private const val SAMPLE_MAX_EDGE = 128

/** 卷积半径（采样像素）：3σ 截断覆盖 99.7% 的能量，再远的权重对 8bit 通道无意义。 */
private const val BLUR_RADIUS = 18

/** 高斯 σ（采样像素）：σ 约为长边的 5%，缩回全屏后读不出海报，但认得出色块。 */
private const val BLUR_SIGMA = 6f

/** 算法版本：一旦改动核公式、通道口径或采样策略就加一，旧缓存自然失效。 */
private const val ALGORITHM_VERSION = 1

/**
 * 图片模糊底图，Coil 3 的 [Transformation]。
 *
 * 为什么不用 `Modifier.blur`：minSdk 是 24，Compose 的模糊只在 31+ 生效，24..30 会原样画出
 * 清晰海报——那正好是"裸封面"要避免的情况。这里用标准 [Bitmap] 与纯数学自己算：
 * 先缩到 [SAMPLE_MAX_EDGE]，再做两遍一维高斯（先横后纵，等价二维高斯，运算量从 r² 降到 2r），
 * 取样越界时 clamp 复制边缘，只对 RGB 加权、Alpha 原样保留。不是马赛克，也不是纯色遮罩。
 *
 * 边界与成本：
 * - 线程：Coil 把变换放在 fetcher 协程上下文（默认 `Dispatchers.IO`）里跑，不占主线程。
 * - 频率：每张图的模糊只在解码后算一次，结果按 [cacheKey] 进 Coil 内存缓存，
 *   滚动、重组、翻页都不会重算，也就不会每帧模糊。
 * - 分辨率：卷积规模只取决于 [sampleMaxEdge]，与手机屏幕和原始图尺寸无关；
 *   128 长边的位图连横带纵也只有几十万次乘加，中低端机同样够快。
 * - 上屏顺序：变换发生在绘制之前，第一帧就是模糊后的位图，不存在"先清晰再模糊"的闪动。
 */
class ImageBlurTransformation(
    private val sampleMaxEdge: Int = SAMPLE_MAX_EDGE,
    private val radius: Int = BLUR_RADIUS,
    private val sigma: Float = BLUR_SIGMA,
) : Transformation() {

    /** 缓存键带全部算法参数：改半径或 σ 就必须重新计算，不能命中旧图。 */
    override val cacheKey: String =
        "imageBlur:v=$ALGORITHM_VERSION:edge=$sampleMaxEdge:r=$radius:sigma=$sigma"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val weights = gaussianKernel(radius = radius, sigma = sigma)
        val sampled = input.sampledTo(sampleMaxEdge).toBuffer()
        return sampled.blurred(weights, vertical = false)
            .blurred(weights, vertical = true)
            .toBitmap()
    }
}

/**
 * 以"模糊底图"的方式请求一张图：解码阶段就按 [SAMPLE_MAX_EDGE] 取小，
 * 保证 [ImageBlurTransformation] 的输入天生是小的；请求本身产不出清晰版本。
 *
 * Coil 只要请求带变换就把位图压回 `ARGB_8888`（HARDWARE 不在 `VALID_TRANSFORMATION_CONFIGS` 里），
 * 所以 [Bitmap.getPixels] 一定读得到，不需要额外的软件位图开关。
 */
fun blurredImageRequest(context: Context, url: String): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .size(SAMPLE_MAX_EDGE)
        .scale(Scale.FIT)
        .transformations(ImageBlurTransformation())
        .build()

/** 一份位图的像素副本：尺寸与像素绑在一起，卷积函数不必每次再传两个标量。 */
private class SampleBuffer(val width: Int, val height: Int, val pixels: IntArray)

private fun Bitmap.toBuffer(): SampleBuffer = SampleBuffer(
    width = width,
    height = height,
    pixels = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) },
)

/** 长边压到 [maxEdge]：只缩小不放大，模糊成本与原始图分辨率解耦。 */
private fun Bitmap.sampledTo(maxEdge: Int): Bitmap {
    val longest = max(width, height)
    if (longest <= maxEdge) return this
    val ratio = maxEdge.toFloat() / longest
    val scaledWidth = max(1, (width * ratio).roundToInt())
    val scaledHeight = max(1, (height * ratio).roundToInt())
    return Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
}

/** 一遍可分离卷积；[vertical] 决定扫列还是扫行。 */
private fun SampleBuffer.blurred(weights: FloatArray, vertical: Boolean): SampleBuffer {
    val output = IntArray(pixels.size)
    for (row in 0 until height) {
        for (column in 0 until width) {
            output[row * width + column] = blurAt(column, row, weights, vertical)
        }
    }
    return SampleBuffer(width, height, output)
}

/**
 * 单像素卷积结果。越界取样点按 clamp 复制边缘——海报边不能被当成透明黑，
 * 否则四周会晕出一圈暗边；Alpha 沿用原像素，只对 RGB 做加权。
 */
private fun SampleBuffer.blurAt(column: Int, row: Int, weights: FloatArray, vertical: Boolean): Int {
    val radius = weights.size / 2
    var red = 0f
    var green = 0f
    var blue = 0f
    for (tap in weights.indices) {
        val offset = tap - radius
        val x = if (vertical) column else (column + offset).coerceIn(0, width - 1)
        val y = if (vertical) (row + offset).coerceIn(0, height - 1) else row
        val argb = pixels[y * width + x]
        val weight = weights[tap]
        red += ((argb shr 16) and 0xFF) * weight
        green += ((argb shr 8) and 0xFF) * weight
        blue += (argb and 0xFF) * weight
    }
    val alpha = (pixels[row * width + column] ushr 24) and 0xFF
    return (alpha shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
}

private fun channel(value: Float): Int = value.roundToInt().coerceIn(0, 255)

/** 归一化一维高斯：exp(-offset² / 2σ²)，权重和归 1，保证整体亮度不变。 */
private fun gaussianKernel(radius: Int, sigma: Float): FloatArray {
    val weights = FloatArray(radius * 2 + 1)
    val denominator = 2f * sigma * sigma
    var total = 0f
    for (index in weights.indices) {
        val offset = (index - radius).toFloat()
        weights[index] = exp(-(offset * offset) / denominator)
        total += weights[index]
    }
    for (index in weights.indices) {
        weights[index] /= total
    }
    return weights
}

private fun SampleBuffer.toBitmap(): Bitmap =
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
