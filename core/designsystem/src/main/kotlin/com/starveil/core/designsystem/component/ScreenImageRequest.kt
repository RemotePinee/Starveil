package com.starveil.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil3.request.ImageRequest
import coil3.size.Scale
import kotlin.math.roundToInt

/**
 * 全屏图的解码请求：目标尺寸钉死在屏幕像素上，而不是"交给 AsyncImage 自己推"。
 *
 * 为什么要有这个函数（真机 `dumpsys meminfo` 实测，1080×2400 @2.625x）：
 * - 一张整屏图按屏幕尺寸解码就是 1080×2400×4 ≈ **10.4MB**，且 API 26+ 走硬件位图，
 *   这笔钱记在 **Graphics / EGL mtrack** 上：首页刚起（开屏 + 首屏海报）EGL 55MB，
 *   滚过一屏后掉到 6MB —— 说明整屏图确实是 10MB 级、且会随缓存驻留。
 * - 实测从图片内容看，页面上没有任何一处是"原图尺寸"解码的：Coil 3 的 `AsyncImage`
 *   按**绘制尺寸**解（`requestOfWithSizeResolver` + `DrawScopeSizeResolver`），
 *   `blurredImageRequest` 则把尺寸钉在 128。所以成本不在"解错分辨率"，
 *   而在"每一张都正好是整屏 10.4MB"，几条一叠就上 100MB。
 *
 * 所以：整屏图的解码尺寸显式给出，并且允许按 [fraction] 打折 ——
 * 0.5 时像素面积降到 1/4（10.4MB → 2.6MB），用于"只短暂压在视频上"的封面；
 * 广告这类真正长期可见的整屏素材传 1.0，只求缓存键稳定、绝不越过屏幕尺寸。
 */
@Composable
public fun rememberScreenImageRequest(url: String?, fraction: Float = 1f): ImageRequest? {
    if (url.isNullOrBlank()) return null
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    val widthDp = configuration.screenWidthDp
    val heightDp = configuration.screenHeightDp
    return remember(url, fraction, widthDp, heightDp, density) {
        ImageRequest.Builder(context)
            .data(url)
            .size(
                width = (widthDp * density * fraction).roundToInt(),
                height = (heightDp * density * fraction).roundToInt(),
            )
            // 与 ContentScale.Crop 同口径：先按短边填满再裁，不走 Scale.FIT 的白边路径。
            .scale(Scale.FILL)
            .build()
    }
}
