package com.starveil.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache

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
    }

    /**
     * 全进程唯一的 ImageLoader。
     *
     * Coil 默认的内存缓存是可用堆的 25%：真机 256MB 堆上等于 64MB，而短剧页一张全屏
     * 封面（1080×2400 的 ARGB_8888）解出来就接近 10MB，再压上播放器的解码缓冲，
     * 滚十几屏之后堆就被缓存本身占满了。压到 15% 把余量让给播放器；
     * 缩下去之后命中率下降的部分由磁盘缓存与网络补，不再靠吃内存换。
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
            .build()

    private companion object {
        /** 可用堆的 15%，见 [newImageLoader]。 */
        const val IMAGE_MEMORY_CACHE_PERCENT = 0.15
    }
}
