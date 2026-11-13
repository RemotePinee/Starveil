package com.starveil.feature.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分流层的行为测试。
 *
 * [VideoCacheKeyTest] 守的是"怎么分类"，这里守的是"分类之后真的分了流"。
 * 少了这一层，把 [SplitDataSourceFactory] 从 `VideoHttp.dataSource()` 里摘掉不会有
 * 任何测试变红，而那就是"清单被缓存 → 分片签名过期 → 源站拒绝"复发的时刻。
 *
 * 走 [dataSourceFactoryFor] 而不是 `SplitDataSource.open(DataSpec)`：本模块单测跑纯 JVM
 * （`unitTests.isReturnDefaultValues = true`，刻意不引 Robolectric），`Uri.parse` 返回 null，
 * `DataSpec` 一构造就 NPE。`open()` 里除了 `dataSpec.uri.toString()` 这一句转接，判断全在这里。
 */
@OptIn(UnstableApi::class)
internal class SplitDataSourceTest {

    /** 只记名字，不做真请求。 */
    private class NamedDataSource(val name: String) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long = 0L
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun getUri(): Uri? = null
        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
        override fun close() = Unit
    }

    /** 造一条地址，返回真正被 createDataSource() 出来的那一侧的名字。 */
    private fun routed(url: String): String {
        val chosen = dataSourceFactoryFor(
            url = url,
            forSegment = DataSource.Factory { NamedDataSource("segment") },
            forManifest = DataSource.Factory { NamedDataSource("manifest") },
        ).createDataSource() as NamedDataSource
        return chosen.name
    }

    @Test
    fun `manifest never reaches the cache factory`() {
        assertEquals("manifest", routed("https://h/c/ncat/1/1.m3u8"))
        assertEquals("manifest", routed("https://h/c/live/playlist?t=1"))
    }

    @Test
    fun `segment always reaches the cache factory`() {
        assertEquals("segment", routed("https://h/c/vod/c5c47bf5.ts?sign=1&timestamp=2"))
        assertEquals("segment", routed("https://h/c/vod/seg-1.m4s"))
    }

    @Test
    fun `unknown falls to the non caching side`() {
        // 判不出来就不缓存：少缓存只是没提速，多缓存是死数据
        assertEquals("manifest", routed("https://h/c/stream?id=1"))
    }
}
