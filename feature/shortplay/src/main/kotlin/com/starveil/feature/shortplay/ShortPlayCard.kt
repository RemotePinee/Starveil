package com.starveil.feature.shortplay

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.starveil.core.model.ShortPlay

/** 视频出画前的全屏封面；元数据与播放入口完全交给 app 注入的播放器。 */
@Composable
internal fun ShortPlayBackdrop(play: ShortPlay) {
    AsyncImage(
        model = play.cover,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}
