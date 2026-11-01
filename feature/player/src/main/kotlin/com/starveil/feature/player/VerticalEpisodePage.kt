package com.starveil.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import com.starveil.core.designsystem.component.BlurredCoverBackdrop

/**
 * 旁页保持不透明；只有当前媒体的有效帧可以透出常驻视频输出。
 *
 * 垫底的是 `BlurredCoverBackdrop`：feed 层滑入页画的也是同一份实现、同一个 URL 的
 * 同一个缓存键，落页那一刻两边的模糊图逐像素一致，没有"先封面再模糊"的过渡。
 */
@Composable
internal fun VerticalEpisodePage(cover: String?, showCover: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().clipToBounds()) {
        if (showCover) BlurredCoverBackdrop(cover)
    }
}
