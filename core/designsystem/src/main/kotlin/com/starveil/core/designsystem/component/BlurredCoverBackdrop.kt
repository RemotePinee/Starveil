package com.starveil.core.designsystem.component

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
import com.starveil.core.designsystem.starveilExtraColors

/**
 * 模糊封面垫底：128px 解码 + 两遍一维高斯，`ContentScale.Crop` 铺满调用方容器。
 *
 * 为什么全工程只有这一份：短剧 feed 的锚点页（视频层自带的底）与滑入页（页面自己铺的底）
 * 必须是**同一张模糊图**。两个调用方都走 [blurredImageRequest]，同一个 URL 的请求大小、
 * 变换与缓存键完全一致，Coil 内存缓存命中，滑入页第一帧拿到的就已经是模糊位图——
 * 不会先画一张清晰封面、等模糊算完再替换（那正是拖动时闪一下原图的来源）。
 *
 * 底色不透明：[blurredImageRequest] 解码要几毫秒，首帧在缓存未命中时是空的。
 * 这里垫 [com.starveil.core.designsystem.starveilExtraColors.scrimStrong] 再叠一层
 * `playerControlBackground`，与视频层那条底同色同暗，接缝看不出来，也不会漏出页面底色。
 */
@Composable
fun BlurredCoverBackdrop(cover: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(context, cover) {
        cover?.takeIf(String::isNotBlank)?.let { blurredImageRequest(context, it) }
    }
    val colors = starveilExtraColors
    Box(modifier = modifier.fillMaxSize().clipToBounds().background(colors.scrimStrong)) {
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
