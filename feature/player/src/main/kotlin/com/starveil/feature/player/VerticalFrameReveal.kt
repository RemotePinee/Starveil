package com.starveil.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.starveil.core.designsystem.token.Motion
import kotlinx.coroutines.delay

/**
 * 遮挡层能不能撤：**上遮挡零延迟，下遮挡晚 [Motion.FrameRevealMs]**。
 *
 * `onRenderedFirstFrame` 只证明解码结果已经渲染进输出；纹理真正上屏还要等下一次绘制遍历，
 * TextureView 走 View 合成，差的正好是那一帧以上。真机上的表现就是"模糊封面已经正常显示，
 * 之后才闪一下黑"——黑的是还没上屏的 TextureView，不是底下那层打底色。所以撤遮挡必须晚于首帧回调。
 *
 * 反方向不延迟：换源的第一帧就得把旧画面盖住（`keepContentOnReset` 留着上一集的最后一帧），
 * 晚一帧就是旧剧残影。这个不对称是刻意的，不是漏写。
 *
 * 换源落在延迟窗口里时这一组组合整体被跳开：`revealed` 连同 `remember` 一起作废，
 * 回到 true 时从 false 重新计时，不会撤到一半就把新源的黑面露出来。
 */
@Composable
internal fun rememberRevealedFrame(hasFrame: Boolean): Boolean {
    // 没有首帧的这一帧就把遮挡放回去，不等协程调度，否则会多露一帧旧画面。
    if (!hasFrame) return false
    var revealed by remember(hasFrame) { mutableStateOf(false) }
    LaunchedEffect(hasFrame) {
        delay(Motion.FrameRevealMs.toLong())
        revealed = true
    }
    return revealed
}
