package com.starveil.feature.user

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import com.starveil.core.designsystem.starveilExtraColors
import kotlin.random.Random

/** 一团柔光：圆心、椭圆半径（占屏宽/屏高的比例）、中心透明度、收尾位置。 */
private class Glow(
    val center: Offset,
    val radii: Size,
    val alpha: Float,
    val fade: Float,
)

/** 彩雾：多团椭圆柔光混色 + 下沿压暗 + 两层粗细不同的颗粒。 */
@Composable
internal fun Modifier.fogBackdrop(): Modifier {
    val accent = starveilExtraColors.accent
    val badge = starveilExtraColors.badge
    val base = MaterialTheme.colorScheme.surface
    val grain = rememberGrain()
    // 高光压在彩雾上：固定白色，不跟主题（浅色主题会被雾吃掉）
    val sheen = starveilExtraColors.onImage
    return drawBehind {
        drawRect(color = base)
        glow(lerp(accent, badge, MIX_FAR), GLOW_FAR)
        glow(accent, GLOW_MAIN)
        glow(badge, GLOW_ALT)
        glow(lerp(accent, badge, MIX_LOW), GLOW_LOW)
        glow(sheen, GLOW_SHEEN)
        // 下沿压一层底：数值和标签压在颗粒上也要看得清
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    SCRIM_FROM to Color.Transparent,
                    1f to base.copy(alpha = SCRIM_ALPHA),
                ),
            ),
            size = size,
        )
        drawRect(
            brush = grainBrush(grain),
            size = size,
            alpha = GRAIN_ALPHA,
        )
        withTransform({
            translate(size.width * 0.5f, size.height * 0.5f)
            scale(GRAIN_COARSE_SCALE, GRAIN_COARSE_SCALE, Offset.Zero)
            translate(-size.width * 0.5f, -size.height * 0.5f)
        }) {
            drawRect(
                brush = grainBrush(grain),
                size = size,
                alpha = GRAIN_COARSE_ALPHA,
            )
        }
    }
}

private fun grainBrush(grain: ImageBitmap): ShaderBrush =
    ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated))

private fun DrawScope.glow(color: Color, g: Glow) {
    val center = Offset(size.width * g.center.x, size.height * g.center.y)
    val radius = size.width * g.radii.width
    withTransform({
        translate(center.x, center.y)
        scale(1f, g.radii.height / g.radii.width, Offset.Zero)
        translate(-center.x, -center.y)
    }) {
        // 画布外扩一圈：纵向压扁后原矩形会缩进去，边缘会露出底色
        drawRect(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to color.copy(alpha = g.alpha),
                    g.fade to color.copy(alpha = 0f),
                ),
                center = center,
                radius = radius,
            ),
            topLeft = Offset(-size.width, -size.height),
            size = Size(size.width * 3f, size.height * 3f),
        )
    }
}

/** 亮、暗噪点各占一半：平均亮度不变，只留颗粒的明暗差。 */
@Composable
private fun rememberGrain(): ImageBitmap = remember {
    val random = Random(GRAIN_SEED)
    val pixels = IntArray(GRAIN_TILE * GRAIN_TILE) {
        val alpha = random.nextInt(GRAIN_MAX_ALPHA)
        val rgb = if (random.nextBoolean()) GRAIN_LIGHT else GRAIN_DARK
        (alpha shl 24) or rgb
    }
    val bitmap = Bitmap.createBitmap(GRAIN_TILE, GRAIN_TILE, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, GRAIN_TILE, 0, 0, GRAIN_TILE, GRAIN_TILE)
    bitmap.asImageBitmap()
}

private val GLOW_FAR = Glow(Offset(0.02f, 1.02f), Size(1.00f, 0.62f), alpha = 0.55f, fade = 0.72f)
private val GLOW_MAIN = Glow(Offset(0.16f, 0.12f), Size(1.00f, 0.72f), alpha = 0.62f, fade = 0.70f)
private val GLOW_ALT = Glow(Offset(1.02f, 0.30f), Size(0.95f, 0.70f), alpha = 0.52f, fade = 0.68f)
private val GLOW_LOW = Glow(Offset(0.82f, 0.94f), Size(0.90f, 0.55f), alpha = 0.45f, fade = 0.70f)
private val GLOW_SHEEN = Glow(Offset(0.55f, 0.02f), Size(0.90f, 0.35f), alpha = 0.10f, fade = 0.75f)
private const val MIX_FAR = 0.35f
private const val MIX_LOW = 0.60f
private const val SCRIM_FROM = 0.40f
private const val SCRIM_ALPHA = 0.55f

private const val GRAIN_ALPHA = 0.42f
private const val GRAIN_COARSE_ALPHA = 0.26f
private const val GRAIN_COARSE_SCALE = 2.6f
private const val GRAIN_TILE = 96
private const val GRAIN_SEED = 20261021
private const val GRAIN_MAX_ALPHA = 255
private const val GRAIN_LIGHT = 0x00FFFFFF
private const val GRAIN_DARK = 0x00000000
