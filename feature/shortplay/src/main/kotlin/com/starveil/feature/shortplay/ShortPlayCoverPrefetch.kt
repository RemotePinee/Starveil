package com.starveil.feature.shortplay

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import com.starveil.core.designsystem.component.prefetchBlurredCover

/** 预取半径：整屏 pager 一次只显示一屏，前后各两屏正好盖住两个滑动方向。 */
private const val COVER_PREFETCH_RADIUS = 2

/**
 * 落页前后几部的模糊封面提前解好，塞进 Coil 内存缓存。
 *
 * 为什么必须在列表阶段做：模糊底走的是 128px + 高斯变换，与卡片原图是**两个缓存键**。
 * 切到那一屏才开始解码，第一帧就没有图可画；提前解好，[BlurredCoverBackdrop] 走同步读缓存
 * 那条路直接命中，切剧第一帧就是模糊的。
 *
 * 节奏：只在落页变化时重扫窗口，每张图只解一次（[prefetched]），滚动过程中不重发请求；
 * 窗口内的图逐张 await，同一时刻只占一个解码位，不跟可见内容抢。
 */
@Composable
internal fun PrefetchBlurredCovers(pages: List<FeedPage>, pager: PagerState) {
    val context = LocalContext.current
    val prefetched = remember { mutableSetOf<String>() }
    val settled = pager.settledPage
    LaunchedEffect(pages, settled) {
        val loader = SingletonImageLoader.get(context)
        for (cover in coversAround(pages, settled)) {
            if (cover.isBlank() || cover in prefetched) continue
            loader.prefetchBlurredCover(context, cover)
            // await 回来才算数：中途被新的落页打断时这张没解完，下次落页还会重试。
            prefetched += cover
        }
    }
}

/** 落页前后各 [COVER_PREFETCH_RADIUS] 屏里的正片封面；广告屏没有封面，跳过。 */
private fun coversAround(pages: List<FeedPage>, settled: Int): List<String> =
    (settled - COVER_PREFETCH_RADIUS..settled + COVER_PREFETCH_RADIUS).mapNotNull { page ->
        (pages.getOrNull(page) as? FeedPage.Play)?.play?.cover
    }
