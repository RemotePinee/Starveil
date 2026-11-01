package com.starveil.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import com.starveil.core.designsystem.component.blurredImageRequest
import com.starveil.core.designsystem.starveilExtraColors

/** 旁页保持不透明；只有当前媒体的有效帧可以透出常驻视频输出。 */
@Composable
internal fun VerticalEpisodePage(cover: String?, showCover: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().clipToBounds()) {
        if (showCover) VerticalBlurredBackdrop(cover)
    }
}

/**
 * 播放页的整页底：还没出首帧时铺模糊封面，出了首帧就换纯深色。
 *
 * 播放起来之后背景不该再模糊：画面周围那几条留边只该是深色，模糊封面留给"起播中 / 换源中"
 * 这段还没有画面可看的时间。切换是硬切，不做淡入淡出——背景晚一百毫秒变化，人只会觉得卡了一下。
 */
@Composable
internal fun VerticalPlaybackBackdrop(cover: String?, hasFrame: Boolean, modifier: Modifier = Modifier) {
    if (hasFrame) {
        Box(modifier = modifier.fillMaxSize().background(starveilExtraColors.scrimStrong))
    } else {
        VerticalBlurredBackdrop(cover, modifier)
    }
}

/** 封面先做真实高斯变换再显示；视频、标题与控件不参与模糊，两种底条复用同一缓存。 */
@Composable
internal fun VerticalBlurredBackdrop(cover: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(context, cover) {
        cover?.takeIf(String::isNotBlank)?.let { blurredImageRequest(context, it) }
    }
    Box(modifier = modifier.fillMaxSize().clipToBounds().background(starveilExtraColors.scrimStrong)) {
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().background(starveilExtraColors.playerControlBackground))
    }
}
