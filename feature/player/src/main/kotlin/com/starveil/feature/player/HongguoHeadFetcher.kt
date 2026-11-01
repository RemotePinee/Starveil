package com.starveil.feature.player

import android.os.SystemClock
import com.starveil.core.hongguo.Mp4SampleTable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * 下载红果片源的头部（ftyp + moov）并解出 sample 表。
 *
 * 这个类存在的理由是分配量：原来每段 `body.bytes()` 各返回一份 136KB、
 * 再 `ByteArrayOutputStream(bytes)` 预分配一份 800KB、`toByteArray()` 又整份复制，
 * 一次头部下载 ≈ 6×136KB + 2×800KB ≈ 2.4MB 的瞬时 byte[]，而每一集都要走一次。
 * 堆被这种大块分配反复撕裂后，24 字节的小对象也会 `Failed to allocate`。
 *
 * 现在全程只有 [rangeRequest] 里的一块 [ByteArray]：各段按自己在文件里的偏移
 * 直写进去，没有中间数组，也没有第二次复制。
 */
internal class HongguoHeadFetcher(
    private val client: OkHttpClient,
    private val headers: Map<String, String>,
) {

    /**
     * 先要 800KB，解不出来再补到 2MB。
     *
     * 800KB 不是拍出来的：moov 实测 782KB，它必须在第一段里，因为 stsz / stco /
     * stsc / senc 全在 moov 里，缺一个都算不出 sample 偏移。
     */
    fun fetch(mainUrl: String, startedAt: Long): Mp4SampleTable? {
        warmUp(mainUrl)
        return headTable(mainUrl, HEAD_BYTES_SMALL, startedAt)
            ?: headTable(mainUrl, HEAD_BYTES, startedAt)
    }

    /** 拉一整份头部并解 moov；解不出来返回 null，由 [fetch] 决定要不要再试更大的那份。 */
    private fun headTable(mainUrl: String, bytes: Int, startedAt: Long): Mp4SampleTable? {
        val head = rangeRequest(mainUrl, bytes) ?: return null
        val table = Mp4SampleTable.parse(head)
        if (table == null) {
            PlayerLog.warn { "moov 解析失败（${bytes} 字节内 stsz/stco/stsc/senc 不全？）" }
        } else {
            logHeadReady(head.size, table, SystemClock.elapsedRealtime() - startedAt)
        }
        return table
    }

    /**
     * 预先建立到 CDN 的连接。
     *
     * 红果每集走的是不同的 CDN 子域名，连接池里没有可复用的连接，
     * 每条新连接都要完整走一遍 DNS + TCP + TLS（稳定在几百毫秒）。
     * 先发一个小请求把连接备好，后面并行的几段就能直接复用。
     *
     * 响应体必须读完再关：[Response.close] 只是把流关掉，没读完的连接
     * OkHttp 判为不可复用，那样这条预热请求等于白发。
     */
    private fun warmUp(mainUrl: String) {
        Thread {
            runCatching {
                val req = Request.Builder().url(mainUrl)
                    .header("Range", "bytes=0-0")
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .build()
                client.newCall(req).execute().use { it.body?.bytes() }
            }
        }.apply { isDaemon = true; name = "hongguo-warmup" }.start()
    }

    /**
     * 分段并行把头部写进同一块内存。
     *
     * 单条 Range 请求在手机上实测要 0.5~2.8 秒，波动很大；切成几段同时拉能
     * 吃满带宽，也把单条连接的抖动摊薄。任何一段没落满就整份判失败。
     */
    internal fun rangeRequest(mainUrl: String, bytes: Int): ByteArray? {
        val head = ByteArray(bytes)
        val part = (bytes + HEAD_PARTS - 1) / HEAD_PARTS
        val filled = AtomicIntegerArray(HEAD_PARTS)
        val latch = CountDownLatch(HEAD_PARTS)
        for (i in 0 until HEAD_PARTS) {
            val chunk = chunkOf(bytes, part, i)
            Thread {
                runCatching { filled.set(i, downloadPart(mainUrl, chunk, head)) }
                latch.countDown()
            }.apply { isDaemon = true; name = "hongguo-head-$i" }.start()
        }
        latch.await(HEAD_TIMEOUT_SEC, TimeUnit.SECONDS)
        return if (contiguous(filled, part)) head else null
    }

    /** 第 [index] 段在文件里的字节区间；末段被 [bytes] 截断，不会写到缓冲外面。 */
    private fun chunkOf(bytes: Int, part: Int, index: Int): Chunk {
        val start = index * part
        return Chunk(start, minOf(start + part, bytes) - 1)
    }

    /**
     * 每段都落满才算头部连续。
     *
     * 末段允许提前结束：那说明文件本来就比 [part] × 段数 短，没写到的尾巴在
     * mdat 里，moov 在前半段已经完整，不影响解析。
     */
    private fun contiguous(filled: AtomicIntegerArray, part: Int): Boolean {
        for (i in 0 until HEAD_PARTS - 1) {
            if (filled.get(i) != part) return false
        }
        return true
    }

    /** 把 [chunk] 这段直接读进 [into] 的对应偏移，返回实际读到多少字节。 */
    private fun downloadPart(mainUrl: String, chunk: Chunk, into: ByteArray): Int {
        val req = Request.Builder().url(mainUrl)
            .header("Range", "bytes=${chunk.start}-${chunk.end}")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        return client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) readFully(resp, into, chunk.start, chunk.end - chunk.start + 1) else 0
        }
    }

    /** 读满 [want] 字节；读不够就返回实际数量，交给 [contiguous] 判失败。 */
    private fun readFully(resp: Response, into: ByteArray, at: Int, want: Int): Int {
        val body = resp.body ?: return 0
        val stream = body.byteStream()
        var got = 0
        while (got < want) {
            val n = stream.read(into, at + got, want - got)
            if (n <= 0) break
            got += n
        }
        return got
    }

    companion object {
        /**
         * moov 实测 782KB，1MB 是上限；先要 800KB 就够绝大多数集。
         *
         * 这一块内存省不掉：moov 本身就 782KB，而 sample 表全在里面。
         */
        private const val HEAD_BYTES_SMALL = 800 * 1024

        /** 800KB 版本解不出来时的兜底：少数集的 moov 超过 800KB。 */
        private const val HEAD_BYTES = 2 * 1024 * 1024

        /**
         * 头部切几段并行。
         *
         * 实测 1 段 2.8s、4 段 1.2s，但每多一段就多一条到 CDN 的连接，
         * 和播放流抢带宽。各段现在直写同一块缓冲，段数只影响连接数，取 3：
         * 比 6 少一半握手，仍然把单连接抖动摊薄到 1/3。
         */
        private const val HEAD_PARTS = 3
        private const val HEAD_TIMEOUT_SEC = 20L
    }
}

/** 头部在文件里的一段：[start]..[end] 闭区间。 */
private class Chunk(val start: Int, val end: Int)

/** 头部多大、表几条、准备了多久，一行打完：起播慢了就先看这一行。 */
private fun logHeadReady(headSize: Int, table: Mp4SampleTable, elapsedMs: Long) {
    PlayerLog.warn {
        "头部 $headSize 字节，sample 表 ${table.samples.size} 条，解密就绪（准备 ${elapsedMs}ms）"
    }
}
