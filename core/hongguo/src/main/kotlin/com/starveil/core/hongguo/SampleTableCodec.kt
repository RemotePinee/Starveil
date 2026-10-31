package com.starveil.core.hongguo

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * sample 表的二进制编解码。
 *
 * 一集的表约 52KB（3251 条 × 16 字节），比 moov 的 782KB 小一个数量级，
 * 存到磁盘后重播同一集就不用再下载头部了。
 *
 * 格式：魔数(4) 版本(1) 条数(4) 然后每条 {偏移(4) 长度(4) IV(8)}。
 */
object SampleTableCodec {

    private const val MAGIC = 0x48475431      // "HGT1"
    private const val VERSION = 1

    fun encode(table: Mp4SampleTable): ByteArray {
        val out = ByteArrayOutputStream(9 + table.samples.size * 16)
        DataOutputStream(out).use { w ->
            w.writeInt(MAGIC)
            w.writeByte(VERSION)
            w.writeInt(table.samples.size)
            for (s in table.samples) {
                w.writeInt(s.offset)
                w.writeInt(s.size)
                w.write(s.iv)
            }
        }
        return out.toByteArray()
    }

    /** 解不出来或格式不对返回 null，调用方按未命中处理。 */
    fun decode(bytes: ByteArray): Mp4SampleTable? = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { r ->
            if (r.readInt() != MAGIC) return null
            if (r.readByte().toInt() != VERSION) return null
            val n = r.readInt()
            if (n <= 0 || n > 5_000_000) return null
            val list = ArrayList<Sample>(n)
            repeat(n) {
                val offset = r.readInt()
                val size = r.readInt()
                val iv = ByteArray(8)
                r.readFully(iv)
                list.add(Sample(offset, size, iv))
            }
            Mp4SampleTable(list)
        }
    }.getOrNull()
}
