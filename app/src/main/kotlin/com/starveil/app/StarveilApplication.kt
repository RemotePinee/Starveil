package com.starveil.app

import android.app.Application
import com.airbnb.lottie.LottieCompositionFactory
import com.starveil.core.designsystem.token.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade

/**
 * 应用入口。刻意保持极薄：
 * - 不做广告/统计/推送 SDK 的无条件初始化（安全基线要求用户同意后再初始化）
 * - 不在这里读本地配置或发网络请求
 *
 * 这里唯一碰的全局资源是 Coil 的图片内存缓存：它不初始化任何 SDK，只决定解码出来的
 * 位图能占多少堆，属于启动期就该定死的事。
 */
class StarveilApplication : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(
            context = this,
            apiHost = getString(R.string.starveil_api_host),
            // 接口签名密钥不得写进仓库（docs/security.md 第 2 节）。
            // 首期占位为空串：后端不校验该参数，接入真实密钥前不影响功能。
            signatureSecret = "",
        )
        preloadSplashLottie()
    }

    /**
     * 开屏 Lottie 预热。
     *
     * [LottieCompositionSpec.Asset] 那条路是异步的：读 asset → 解析 JSON → 建图层树，
     * 一百来 KB 的素材要几十到几百毫秒，这段时间屏幕是空的——点图标之后"要等一下才看见动画"
     * 就是这个原因，跟素材大小关系不大。
     *
     * [LottieCompositionFactory] 解析完会写进自己的内存缓存，按 asset 名命中。这里在
     * onCreate 里把两套素材都发起一次解析（异步，不阻塞主线程），等 SplashBrandLayer
     * 去取的时候基本已经好了，首帧就能出图。
     *
     */
    private fun preloadSplashLottie() {
        // fromAsset 是同步的，放主线程等于把解析时间直接加到冷启动上。
        // 丢到 IO 线程解析，解析结果会进 Lottie 自己的内存缓存，SplashBrandLayer 取时命中。
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { LottieCompositionFactory.fromAsset(this@StarveilApplication, SPLASH_DARK_ASSET) }
            runCatching { LottieCompositionFactory.fromAsset(this@StarveilApplication, SPLASH_LIGHT_ASSET) }
        }
    }

    /**
     * 全进程唯一的 ImageLoader。
     *
     * 真机实测（1080×2400 @2.625x，`dumpsys meminfo` 每屏一采）：一张整屏图解码后是
     * 1080×2400×4 ≈ 10.4MB，API 26+ 走硬件位图，全部记在 **Graphics / EGL mtrack** 上；
     * 首页刚起 EGL 55MB、滚一屏掉回 6MB，短剧 feed 滑 4 屏 Graphics 从 55MB 爬到 138MB 并停住。
     * 也就是说 Graphics 的稳态高度 = **缓存里能塞下几张整屏图**。
     *
     * 原来取可用堆的 15%：256MB 堆 = 38MB ≈ 只放得下三张整屏图，可长图/广告素材一旦
     * 叠上封面与视频面缓冲，Graphics 立刻过百。压到 8% 把稳态压到两张整屏图以内，
     * 缩下去之后命中率下降的部分由磁盘缓存与网络补，不再靠吃 GPU 内存换。
     *
     * 磁盘缓存仍走 Coil 默认（`cacheDir/image_cache` 的 2% 空间），这里不覆盖。
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, IMAGE_MEMORY_CACHE_PERCENT)
                    .build()
            }
            // 图从空位"跳"出来是首页最扎眼的一处生硬：统一补一段淡入，
            // 磁盘/网络回来的图都是渐出来的。命中内存缓存不会重放，滑动时不拖影。
            .crossfade(Motion.ShortMs)
            .build()

    private companion object {
        /** 可用堆的 8%，见 [newImageLoader]。 */
        const val IMAGE_MEMORY_CACHE_PERCENT = 0.08
        /** 深色模式的开屏素材。 */
        const val SPLASH_DARK_ASSET = "splash_loading.json"
        /** 浅色模式的开屏素材。 */
        const val SPLASH_LIGHT_ASSET = "splash_loading_light.json"
    }
}
