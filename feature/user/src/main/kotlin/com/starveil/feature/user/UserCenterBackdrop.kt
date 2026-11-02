package com.starveil.feature.user

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.painterResource

/**
 * 「我的」顶部插画背景。
 *
 * 之前这里是代码画的彩雾（4 团径向渐变 + 两层颗粒噪点）。换成一张扁平插画后颗粒层一并去掉：
 * 图本身够干净，再叠噪点只会显脏。原来那套 glow / grain 的参数和辅助函数也一起删了，
 * 留着就是死代码。
 *
 * 图片按 Crop 铺满：取较大的一边缩放、居中，超出部分裁掉。图是 4:3，hero 区约 1.27:1，
 * 所以裁掉的是左右各一点——左边留白和右边人物都保得住。
 *
 * 下沿压一层到 surface 的渐变：积分 / 金币 / 观看时长那排数压在图上也要看得清。
 */
@Composable
internal fun Modifier.fogBackdrop(): Modifier {
    val painter = painterResource(R.drawable.feature_user_backdrop)
    val base = MaterialTheme.colorScheme.surface
    return drawBehind {
        val pw = painter.intrinsicSize.width
        val ph = painter.intrinsicSize.height
        if (pw > 0f && ph > 0f) {
            val scale = maxOf(size.width / pw, size.height / ph)
            translate(
                left = (size.width - pw * scale) / 2f,
                top = (size.height - ph * scale) / 2f,
            ) {
                with(painter) { draw(Size(pw * scale, ph * scale)) }
            }
        } else {
            // 图片还没解码完的首帧：先铺底色，避免透出上层容器
            drawRect(color = base)
        }
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    SCRIM_FROM to Color.Transparent,
                    1f to base.copy(alpha = SCRIM_ALPHA),
                ),
            ),
            size = size,
        )
    }
}

/** 压暗渐变的起点：上方到这个比例为止完全透明，把插画完整留给眼睛。 */
private const val SCRIM_FROM = 0.55f

/** 底边的压暗浓度：够压住插画的细节让数字可读，又不至于糊成一片。 */
private const val SCRIM_ALPHA = 0.35f
