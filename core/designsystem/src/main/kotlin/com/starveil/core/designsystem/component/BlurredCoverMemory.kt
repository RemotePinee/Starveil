package com.starveil.core.designsystem.component

import android.content.Context
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest

/**
 * 模糊封面在 Coil 内存缓存里的键。
 *
 * 显式给键、不交给 Coil 现算：解好的模糊位图要能被**同步**取出来（见 [blurredCoverPainter]），
 * 而默认键里带着 `coil#size` 这种内部 extras，模块外拼不出同一个 `MemoryCache.Key`。
 * 前缀串上 [ImageBlurTransformation.cacheKey]（含算法版本、半径与 σ）：改算法就换键，
 * 旧结果自动失效，不会画出一张按旧参数算的糊图。
 */
internal fun blurredCoverCacheKey(url: String): String =
    "blurredCover:${ImageBlurTransformation().cacheKey}:$url"

/**
 * 同步取一张**已经解好**的模糊封面；没有就返回 null。
 *
 * 这是"切剧第一帧就是模糊的"落点：`SubcomposeAsyncImage` 即便命中内存缓存也要过一轮协程，
 * 那一帧画不出图（换成打底色或上一帧）；这里直接从 [MemoryCache] 拿位图，本帧就能画。
 * 拿不到时调用方铺打底色，**绝不去请求原图**——清晰封面从这条路上不可能出现。
 */
internal fun blurredCoverPainter(context: Context, request: ImageRequest): Painter? {
    // 键由 blurredImageRequest 显式给出；extras 里带着变换串（transformations() 写的），必须一起带上。
    val cached = request.memoryCacheKey?.let { key ->
        val cache = SingletonImageLoader.get(context).memoryCache
        cache?.get(MemoryCache.Key(key, request.memoryCacheKeyExtras))
    }
    val bitmap = (cached?.image as? BitmapImage)?.bitmap
    return bitmap?.let { BitmapPainter(it.asImageBitmap()) }
}

/**
 * 预取一张模糊封面进内存缓存，自己不绘制任何东西。
 *
 * Coil 3 没有请求优先级（Coil 2 的 `Priority` 已移除），"低优先级"落在**调用方式**上：
 * 调用方逐张 await 这个挂起函数（见 `PrefetchBlurredCovers`），预取同一时刻只占一个解码位，
 * 不跟可见内容抢线程。取回、解码与高斯变换都由 Coil 在 fetcher 上下文（IO）里完成，不占主线程。
 */
public suspend fun ImageLoader.prefetchBlurredCover(context: Context, url: String) {
    execute(blurredImageRequest(context, url))
}
