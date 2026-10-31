package com.starveil.core.hongguo

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** mp4 里一个 sample 的文件偏移、长度和它的 CENC IV（8 字节）。 */
class Sample(val offset: Int, val size: Int, val iv: ByteArray)

/** 一块待解密的内存：从 [buf] 的 [off] 开始、共 [len] 字节。 */
class Region(val buf: ByteArray, val off: Int, val len: Int)

/**
 * mp4 的 sample 表：从 moov 的 stbl 里解出 stsz/stco/stsc/senc，
 * 展开成「sample 序号 -> 文件偏移」的列表。
 *
 * 只处理普通（非分片）mp4 —— 红果的片子就是这种结构：
 * ftyp + moov + free + mdat。
 */
class Mp4SampleTable(val samples: List<Sample>) {

    /**
     * 就地解密 [region] 这段字节。
     *
     * 一次 read 可能横跨多个 sample，也可能只落在某个 sample 中间（Range 请求），
     * 所以按 sample 边界切段，段内再按 16 字节块对齐算 keystream 偏移。
     */
    fun decryptRange(region: Region, from: Long, key: ByteArray) {
        if (region.len <= 0) return
        for (s in samples) decryptOne(region, s, from, key)
    }

    /** 解 [s] 与 [from] 起 [region.len] 字节这段区间的交集；不相交就什么都不做。 */
    private fun decryptOne(region: Region, s: Sample, from: Long, key: ByteArray) {
        val sStart = s.offset.toLong()
        val segStart = maxOf(from, sStart)
        val segEnd = minOf(from + region.len, sStart + s.size)
        if (segEnd <= segStart) return

        val dstOff = region.off + (segStart - from).toInt()
        val segLen = (segEnd - segStart).toInt()
        val inSample = (segStart - sStart).toInt()
        val cipher = region.buf.copyOfRange(dstOff, dstOff + segLen)
        val start = CencSampleDecryptor.Start(block = inSample / 16, skip = inSample % 16)
        val plain = CencSampleDecryptor.decryptAt(key, s.iv, cipher, start)
        System.arraycopy(plain, 0, region.buf, dstOff, segLen)
    }

    companion object {
        private const val BOX_MOOV = "moov"
        private const val BOX_TRAK = "trak"
        private const val BOX_MDIA = "mdia"
        private const val BOX_MINF = "minf"
        private const val BOX_STBL = "stbl"

        private const val NORMAL_HEADER_LEN = 8
        private const val LARGE_HEADER_LEN = 16

        /** stbl 下算偏移必不可少的子 box 个数：senc / stsz / stco / stsc。 */
        private const val REQUIRED_BOXES = 4

        /** 从完整 mp4 头部（moov 所在的那些字节）解析。失败返回 null。 */
        fun parse(mp4: ByteArray): Mp4SampleTable? {
            val moov = findBox(mp4, 0, mp4.size, BOX_MOOV) ?: return null
            val out = ArrayList<Sample>()
            for (trak in findBoxes(mp4, moov.start, moov.end, BOX_TRAK)) {
                collectTrak(mp4, trak.start, trak.end, out)
            }
            return if (out.isEmpty()) null else Mp4SampleTable(out)
        }

        /** 一条轨道：逐层剥 mdia -> minf -> stbl，再按 chunk 摊开。 */
        private fun collectTrak(b: ByteArray, start: Int, end: Int, out: MutableList<Sample>) {
            val stbl = stblOf(b, start, end)
            val boxes = stbl?.let { sampleBoxesIn(b, it) }
            if (boxes == null) return
            val chunkOffsets = readChunkOffsets(b, boxes.stco)
            val parts = TableParts(
                sizes = readSizes(b, boxes.stsz),
                chunkOffsets = chunkOffsets,
                perChunk = readSamplesPerChunk(b, boxes.stsc, chunkOffsets.size),
                ivs = readIvs(b, boxes.senc),
            )
            expandSamples(parts, out)
        }

        /** mdia -> minf -> stbl；任一层缺失就不是我们认得的轨道。 */
        private fun stblOf(b: ByteArray, start: Int, end: Int): Box? =
            findBox(b, start, end, BOX_MDIA)
                ?.let { findBox(b, it.start, it.end, BOX_MINF) }
                ?.let { findBox(b, it.start, it.end, BOX_STBL) }

        /** stbl 下算偏移要用到的四个子 box。 */
        private class SampleBoxes(
            val senc: Box,
            val stsz: Box,
            val stco: Box,
            val stsc: Box,
        )

        /**
         * 取 stbl 的四个子 box。
         *
         * 音轨没有 senc，就说明那是没加密的轨道；四个缺任何一个都算不出偏移，一并跳过。
         */
        private fun sampleBoxesIn(b: ByteArray, stbl: Box): SampleBoxes? {
            val found = listOfNotNull(
                findBox(b, stbl.start, stbl.end, "senc"),
                findBox(b, stbl.start, stbl.end, "stsz"),
                findBox(b, stbl.start, stbl.end, "stco"),
                findBox(b, stbl.start, stbl.end, "stsc"),
            )
            return if (found.size == REQUIRED_BOXES) {
                SampleBoxes(found[0], found[1], found[2], found[3])
            } else {
                null
            }
        }

        /** stbl 里读出来的四张表，展开时一起用。 */
        private class TableParts(
            val sizes: List<Int>,
            val chunkOffsets: List<Int>,
            val perChunk: IntArray,
            val ivs: List<ByteArray>,
        )

        private class Box(val start: Int, val end: Int)

        /** 一个 box 头：[bodyStart] 是内容起点，[bodyEnd] 是内容终点（不含），[total] 是整盒长度。 */
        private class Header(
            val type: String,
            val bodyStart: Int,
            val bodyEnd: Int,
            val total: Int,
        )

        private fun u32(b: ByteArray, at: Int): Int =
            ByteBuffer.wrap(b, at, 4).order(ByteOrder.BIG_ENDIAN).int

        private fun findBoxes(b: ByteArray, start: Int, end: Int, type: String): List<Box> {
            val out = ArrayList<Box>()
            var off = start
            while (off + NORMAL_HEADER_LEN <= end) {
                // 头都读不出来，说明剩下的字节已经不是 box 结构了
                val header = readHeader(b, off, end) ?: break
                if (header.type == type) out.add(Box(header.bodyStart, header.bodyEnd))
                off += header.total
            }
            return out
        }

        /**
         * 读 [at] 处的 box 头。
         *
         * 长度字段有三种含义：0 = 延续到父 box 末尾，1 = 后面跟 64 位的 largesize，
         * 其余就是 box 总长。算下来越界就返回 null，让调用方停止遍历。
         */
        private fun readHeader(b: ByteArray, at: Int, end: Int): Header? {
            val type = String(b, at + 4, 4, Charsets.ISO_8859_1)
            val raw = u32(b, at)
            val large = raw == 1
            // largesize 又要 8 个字节，父 box 里放不下就按结构损坏处理
            if (large && at + LARGE_HEADER_LEN > end) return null
            val declared = if (large) ByteBuffer.wrap(b, at + 8, 8).long.toInt() else raw
            val total = if (raw == 0) end - at else declared
            val headerLen = if (large) LARGE_HEADER_LEN else NORMAL_HEADER_LEN
            return if (total < headerLen || at + total > end) {
                null
            } else {
                Header(type, at + headerLen, at + total, total)
            }
        }

        private fun findBox(b: ByteArray, start: Int, end: Int, type: String): Box? =
            findBoxes(b, start, end, type).firstOrNull()

        private fun readSizes(b: ByteArray, stsz: Box): List<Int> {
            val fixed = u32(b, stsz.start + 4)
            val count = u32(b, stsz.start + 8)
            if (fixed != 0) return List(count) { fixed }
            return List(count) { u32(b, stsz.start + 12 + it * 4) }
        }

        private fun readChunkOffsets(b: ByteArray, stco: Box): List<Int> {
            val count = u32(b, stco.start + 4)
            return List(count) { u32(b, stco.start + 8 + it * 4) }
        }

        /** stsc 展开成「每个 chunk 里几个 sample」。 */
        private fun readSamplesPerChunk(b: ByteArray, stsc: Box, chunkCount: Int): IntArray {
            val n = u32(b, stsc.start + 4)
            val out = IntArray(chunkCount) { 1 }
            if (n == 0) return out
            val rows = ArrayList<Triple<Int, Int, Int>>(n)
            for (i in 0 until n) {
                val base = stsc.start + 8 + i * 12
                rows.add(Triple(u32(b, base), u32(b, base + 4), u32(b, base + 8)))
            }
            for (i in rows.indices) {
                val nextChunk = if (i + 1 < rows.size) rows[i + 1].first else chunkCount + 1
                fillChunks(out, rows[i].first, nextChunk, rows[i].second)
            }
            return out
        }

        /** 一行 stsc 描述的是 [fromChunk, toChunk) 这些 chunk，1 起算。 */
        private fun fillChunks(out: IntArray, fromChunk: Int, toChunk: Int, perChunk: Int) {
            for (ci in (fromChunk - 1) until (toChunk - 1)) {
                if (ci in out.indices) out[ci] = perChunk
            }
        }

        /** 按 chunk 把 sample 依次摊开，返回每个 sample 的文件偏移（只含 chunk 覆盖到的那些）。 */
        private fun sampleOffsets(parts: TableParts): IntArray {
            val offsets = ArrayList<Int>(parts.sizes.size)
            var si = 0
            for (ci in parts.chunkOffsets.indices) {
                var off = parts.chunkOffsets[ci]
                var taken = 0
                while (taken < parts.perChunk[ci] && si < parts.sizes.size) {
                    offsets.add(off)
                    off += parts.sizes[si]
                    si++
                    taken++
                }
            }
            return offsets.toIntArray()
        }

        private fun expandSamples(parts: TableParts, out: MutableList<Sample>) {
            val offsets = sampleOffsets(parts)
            for (i in offsets.indices) {
                // IV 个数少于 sample 时，多出来的按「没登记 IV」处理，与原来一致
                if (i < parts.ivs.size) out.add(Sample(offsets[i], parts.sizes[i], parts.ivs[i]))
            }
        }

        private fun readIvs(b: ByteArray, senc: Box): List<ByteArray> {
            val count = u32(b, senc.start + 4)
            return List(count) { i ->
                val at = senc.start + 8 + i * 8
                if (at + 8 > senc.end) ByteArray(8) else b.copyOfRange(at, at + 8)
            }
        }
    }
}
