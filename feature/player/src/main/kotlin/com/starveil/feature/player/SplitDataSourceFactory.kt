package com.starveil.feature.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * 按资源类型分流的 DataSource。
 *
 * 为什么需要这一层：`CacheDataSource` 会把经过它的一切都写进 [VideoCache]，
 * 包括 m3u8 清单。而清单的内容随每次请求变（分片签名、广告切片、时长），
 * 缓存住的后果是客户端拿着过期的分片签名去请求，源站回 403，
 * 播放器显示"这条线路被源站拒绝了"——只有清缓存才能恢复。
 *
 * `CacheDataSource.Factory.setCacheKeyFactory` 拿不到响应头，也没有"这条不缓存"的开关
 * （返回 null 会 NPE），所以分流只能在 open 之前按 URL 判定。
 *
 * 判定规则见 [classifyPlaybackResource]：判不出来的按不缓存处理。
 * 少缓存的代价是没提速，多缓存的代价是死链。
 */
@OptIn(UnstableApi::class)
internal class SplitDataSourceFactory(
    private val forSegment: DataSource.Factory,
    private val forManifest: DataSource.Factory,
) : DataSource.Factory {

    override fun createDataSource(): DataSource =
        SplitDataSource(forSegment, forManifest)
}

/**
 * 这条地址该走哪个 Factory。
 *
 * 单独成函数是为了能被纯 JVM 单测直接钉住（[SplitDataSourceFactory] 的 `open()` 只剩
 * 一句 `dataSpec.uri.toString()` 的转接，中间没有别的判断）：把
 * [SplitDataSourceFactory] 从 `VideoHttp.dataSource()` 里摘掉、或者把两个 Factory 传反，
 * 都必须有测试变红——那正是"清单被缓存 → 分片签名过期 → 源站拒绝"复发的时刻。
 */
internal fun dataSourceFactoryFor(
    url: String,
    forSegment: DataSource.Factory,
    forManifest: DataSource.Factory,
): DataSource.Factory =
    if (classifyPlaybackResource(url) == ResourceKind.SEGMENT) forSegment else forManifest

@UnstableApi
private class SplitDataSource(
    private val segmentFactory: DataSource.Factory,
    private val manifestFactory: DataSource.Factory,
) : DataSource {

    /**
     * 实际选中的下游。
     *
     * 用 [AtomicReference] 而不是普通字段：Media3 允许在播放线程 open、
     * 在 loader 线程 read，两者之间要有明确的内存可见性。
     */
    private val delegate = AtomicReference<DataSource?>(null)

    /**
     * 监听器必须先存起来。
     *
     * addTransferListener 在 open 之前调用，那时还不知道这条是清单还是分片；
     * 如果在这里就 createDataSource() 挂上去，会建出一个用不上的实例。
     */
    private val listeners = ArrayList<TransferListener>(2)

    override fun addTransferListener(transferListener: TransferListener) {
        synchronized(listeners) { listeners += transferListener }
        delegate.get()?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val target = dataSourceFactoryFor(
            url = dataSpec.uri.toString(),
            forSegment = segmentFactory,
            forManifest = manifestFactory,
        ).createDataSource()
        synchronized(listeners) { listeners.forEach(target::addTransferListener) }
        // 上一条没 close 就再 open 时，旧的下游（可能已持有连接）在这里收掉，
        // 否则每次重试都留一个没人关的 DataSource。
        delegate.getAndSet(target)?.close()
        return target.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val target = delegate.get() ?: throw IOException("read() called before open()")
        return target.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = delegate.get()?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate.get()?.responseHeaders ?: emptyMap()

    override fun close() {
        val target = delegate.getAndSet(null) ?: return
        target.close()
    }
}
