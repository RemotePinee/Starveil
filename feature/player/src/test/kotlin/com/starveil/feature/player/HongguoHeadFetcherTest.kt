package com.starveil.feature.player

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections

/**
 * 分段并行直写同一块缓冲的回归测试。
 *
 * 头部是 moov 的唯一来源，拼错一个字节 sample 表就解不出来，播放器只剩黑屏；
 * 这里用假 CDN 按 Range 逐段回字节，逐位核对拼出来的头部。
 */
class HongguoHeadFetcherTest {

    @Test
    fun `parallel parts are reassembled byte for byte`() {
        val (fetcher, ranges) = fetcher(limit = SIZE)

        val head = fetcher.rangeRequest(URL, SIZE)!!

        assertArrayEquals(ByteArray(SIZE) { it.toByte() }, head)
        assertEquals(listOf("bytes=0-9999", "bytes=10000-19999", "bytes=20000-29999"), ranges.sorted())
    }

    /** 末段提前结束说明文件比要的短：前面的字节必须完整，尾巴留零（moov 在前半段）。 */
    @Test
    fun `a file shorter than requested keeps the prefix and leaves the tail empty`() {
        val (fetcher, _) = fetcher(limit = 25_000)

        val head = fetcher.rangeRequest(URL, SIZE)!!

        assertEquals(24_999.toByte(), head[24_999])
        assertEquals(0.toByte(), head[25_000])
    }

    /** 假 CDN：只回 [limit] 字节，按 Range 切，并记下每次请求的区间。 */
    private fun fetcher(limit: Int): Pair<HongguoHeadFetcher, MutableList<String>> {
        val ranges = Collections.synchronizedList(mutableListOf<String>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val header = chain.request().header("Range").orEmpty()
            ranges += header
            val match = RANGE.find(header) ?: error("请求没带 Range: $header")
            val start = match.groupValues[1].toInt()
            val end = minOf(match.groupValues[2].toInt(), limit - 1)
            val body = ByteArray(maxOf(end - start + 1, 0)) { (start + it).toByte() }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(206)
                .message("Partial Content")
                .body(body.toResponseBody(null))
                .build()
        }.build()
        return HongguoHeadFetcher(client, emptyMap()) to ranges
    }

    private companion object {
        const val URL = "https://cdn.example.com/video.mp4"

        /** 30KB：够切 3 段、够快，和线上的 800KB 是同一套算法。 */
        const val SIZE = 30_000

        val RANGE = Regex("bytes=(\\d+)-(\\d+)")
    }
}
