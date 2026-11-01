package com.starveil.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import com.starveil.core.designsystem.component.BlurredCoverBackdrop
import com.starveil.core.designsystem.starveilExtraColors

/** 旁页保持不透明；只有当前媒体的有效帧可以透出常驻视频输出。换页只拨 alpha，不重建封面。 */
@Composable
internal fun VerticalEpisodePage(cover: String?, showCover: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().clipToBounds()) {
        VerticalBlurredBackdrop(cover = cover, modifier = Modifier.visibleAlpha(showCover))
    }
}

/**
 * 常驻层只改绘制 alpha，不增删节点。
 *
 * 用 `if (visible)` 条件组合整块封面，每次切源都会重新组合出一个 `AsyncImage`：它的
 * painter 从 Empty 起步，要等 Coil 下一轮才出图，那一帧露出来的是打底色或视频面——
 * 就是"闪一下"。节点留着、只把 alpha 拨到 0，封面始终是上一次已经解好的那张位图。
 */
internal fun Modifier.visibleAlpha(visible: Boolean): Modifier =
    graphicsLayer { alpha = if (visible) 1f else 0f }

/**
 * 播放页的整页底：还没出首帧时铺模糊封面，出了首帧就换纯深色。
 *
 * 播放起来之后背景不该再模糊：画面周围那几条留边只该是深色，模糊封面留给"起播中 / 换源中"
 * 这段还没有画面可看的时间。切换用调用方给的 [revealed]：**延迟到首帧真正上屏之后**，
 * 与遮挡层的撤除同一帧发生——提前换深色会出现"背景已经黑了、画面还盖着模糊封面"的跳变。
 * 深色底常驻，封面层也只拨 alpha：换源时不重新组合，才不会有一帧没有图的空档。
 */
@Composable
internal fun VerticalPlaybackBackdrop(cover: String?, revealed: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().background(starveilExtraColors.scrimStrong)) {
        VerticalBlurredBackdrop(cover = cover, modifier = Modifier.visibleAlpha(!revealed))
    }
}

/**
 * 封面先做真实高斯变换再显示；视频、标题与控件不参与模糊，两种底条复用同一缓存。
 *
 * "换封面保留上一张"的逻辑封在 [BlurredCoverBackdrop] 内部：本模块所有调用点
 * （旁页、页面级底、遮挡层）都走这一个入口，不必各自再写一份，也就不会漏。
 */
@Composable
internal fun VerticalBlurredBackdrop(cover: String?, modifier: Modifier = Modifier) {
    BlurredCoverBackdrop(cover = cover, modifier = modifier)
}
