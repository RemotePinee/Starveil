package com.starveil.feature.shortplay

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.starveil.core.designsystem.component.rememberScreenImageRequest
import com.starveil.core.model.ShortPlay

/**
 * 只短暂压在视频上的封面按半屏像素解码。
 *
 * 视频出画后这一层立刻被盖住，用户看到的是"色块对了、构图对了"的一瞬间；
 * 按整屏解是 1080×2400×4 ≈ 10.4MB 一张（真机实测记在 Graphics / EGL mtrack 上），
 * 半屏解只要 2.6MB，画面上看不出区别。整屏素材的说明见 [rememberScreenImageRequest]。
 */
private const val COVER_DECODE_FRACTION = 0.5f

/** 视频出画前的全屏封面；元数据与播放入口完全交给 app 注入的播放器。 */
@Composable
internal fun ShortPlayBackdrop(play: ShortPlay) {
    AsyncImage(
        model = rememberScreenImageRequest(play.cover, COVER_DECODE_FRACTION),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}
