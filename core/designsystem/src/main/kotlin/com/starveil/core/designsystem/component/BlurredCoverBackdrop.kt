package com.starveil.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import com.starveil.core.designsystem.starveilExtraColors

/**
 * 模糊封面底：换封面时**保留上一张已解好的模糊图**，直到新图解码完成再切换。
 *
 * Coil 的 painter 在 `model` 变化的那一瞬间就回到 `Empty`，解码走协程、至少空一帧；
 * 这一帧如果只剩打底色，换剧时屏幕就是整块 `scrimStrong`——就是"切剧闪黑"。
 * 所以这里观察 [AsyncImagePainter.State]：`Success` 时把 painter 存进 [lastPainter]，
 * 其余状态继续画它；只有冷启动（一张都还没成功过）才落到打底色。
 *
 * [lastPainter] **故意不随 [cover] 变化清空**——它兜的正是换封面那一帧。
 */
@Composable
fun BlurredCoverBackdrop(cover: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(context, cover) {
        cover?.takeIf(String::isNotBlank)?.let { blurredImageRequest(context, it) }
    }
    var lastPainter by remember { mutableStateOf<Painter?>(null) }
    val scrim = starveilExtraColors.scrimStrong
    Box(modifier = modifier.fillMaxSize().clipToBounds().background(scrim)) {
        if (request != null) {
            SubcomposeAsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            ) {
                val state by painter.state.collectAsState()
                val success = state as? AsyncImagePainter.State.Success
                val shown = success?.painter ?: lastPainter
                SideEffect { if (success != null) lastPainter = success.painter }
                if (shown != null) {
                    Image(
                        painter = shown,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Box(Modifier.fillMaxSize().background(starveilExtraColors.playerControlBackground))
    }
}
