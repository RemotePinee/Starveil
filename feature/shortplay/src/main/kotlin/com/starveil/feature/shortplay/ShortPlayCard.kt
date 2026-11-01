package com.starveil.feature.shortplay

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.starveil.core.designsystem.component.BlurredCoverBackdrop
import com.starveil.core.model.ShortPlay

/**
 * 视频出画前的全屏封面；元数据与播放入口完全交给 app 注入的播放器。
 *
 * 走 [BlurredCoverBackdrop]：与视频层那条底用**同一个函数、同一个 URL**
 * （128px 解码 + `ImageBlurTransformation`），请求尺寸、变换与缓存键完全一致，
 * Coil 内存缓存直接命中。「换封面保留上一张模糊图」也在那里实现，
 * feed 换剧时这一层不会露出打底色。
 */
@Composable
internal fun ShortPlayBackdrop(play: ShortPlay) {
    BlurredCoverBackdrop(cover = play.cover, modifier = Modifier.fillMaxSize())
}
