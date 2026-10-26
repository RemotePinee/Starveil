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
