package com.starveil.feature.shortplay

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
import com.starveil.core.model.ShortPlay

/**
 * 视频出画前的全屏封面；元数据与播放入口完全交给 app 注入的播放器。
 *
 * 走 [blurredImageRequest]（128px 解码 + `ImageBlurTransformation`），与视频层那条底
 * 用**同一个函数、同一个 URL**：请求尺寸、变换与缓存键完全一致，Coil 内存缓存直接命中，
 * 滑入页第一帧画出来的就是模糊位图——不会先画原图、等模糊算完再替换（那正是切剧时闪一下的源头）。
 *
 * 底色不透明：缓存未命中时解码要几毫秒，那一帧垫 [starveilExtraColors.scrimStrong]
 * 再叠 `playerControlBackground`，与视频层底同色同暗，既不漏页面底色也没有接缝。
 */
@Composable
internal fun ShortPlayBackdrop(play: ShortPlay) {
    val context = LocalContext.current
    val cover = play.cover
    val request = remember(context, cover) {
        cover?.takeIf(String::isNotBlank)?.let { blurredImageRequest(context, it) }
    }
    val colors = starveilExtraColors
    Box(Modifier.fillMaxSize().clipToBounds().background(colors.scrimStrong)) {
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().background(colors.playerControlBackground))
    }
}
