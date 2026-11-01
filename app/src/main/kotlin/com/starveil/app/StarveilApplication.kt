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
            .build()

    private companion object {
        /** 可用堆的 8%，见 [newImageLoader]。 */
        const val IMAGE_MEMORY_CACHE_PERCENT = 0.08
    }
}
