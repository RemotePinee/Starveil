package com.starveil.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.SubcomposeAsyncImage
import com.starveil.core.designsystem.starveilExtraColors

/**
 * 模糊封面底：**第一帧就只能是模糊的**，任何路径都不画清晰原图。
 *
 * - 命中内存缓存（列表阶段已预取，或刚解过同一部剧）：走 [blurredCoverPainter] 同步取位图，
 *   一次异步请求都不发，本帧即模糊。请求键是 128px + 高斯变换，与卡片原图不是同一个键，
 *   所以不存在"原图先命中、之后再变糊"。
 * - 未命中（新剧、冷缓存、预取还没跑完）：只铺 [starveilExtraColors.scrimStrong] 打底色，
 *   等 Coil 解完再画上来。打底色不是封面，宁可黑一帧也不给清晰图。
 *
 * 这里曾经保留"上一张成功的图"来兜换图那一帧：留住的是**上一部剧**的封面，用户明确不要残影；
 * 而且要盖的是解码延迟而不是空帧，撤掉之后空档交给打底色，图只认当前 [cover]。
 */
@Composable
fun BlurredCoverBackdrop(cover: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(context, cover) {
        cover?.takeIf(String::isNotBlank)?.let { blurredImageRequest(context, it) }
    }
    val cached = remember(context, request) { request?.let { blurredCoverPainter(context, it) } }
    val scrim = starveilExtraColors.scrimStrong
    Box(modifier = modifier.fillMaxSize().clipToBounds().background(scrim)) {
        if (cached != null) {
            CoverImage(cached)
        } else if (request != null) {
            SubcomposeAsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                success = { CoverImage(painter) },
            )
        }
        Box(Modifier.fillMaxSize().background(starveilExtraColors.playerControlBackground))
    }
}

@Composable
private fun CoverImage(painter: Painter) {
    Image(
        painter = painter,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}
