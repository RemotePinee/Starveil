package com.starveil.core.hongguo

import java.io.ByteArrayOutputStream

/**
 * spade_a -> 16 字节 AES-128 key。
 *
 * spade_a 是一段变形过的 base64（非标准表），解码后还要做一轮按位变换，
 * 最后得到 32 个 hex 字符。自检样本：
 *   krwcy0yMBcFVti37Y7ce51KuGtVLrDb9SYQH/nuHKsh7sTOzsw==
 *   -> 3a91268aa665e8331d9be9fb6bca867c
 */
object SpadeKey {

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** 前三个字节参与算 body 长度，比这更短的输入不可能是 spade_a。 */
    private const val MIN_BYTES = 3

    /** 一集 key 的 hex 长度：32 个 hex 字符 = 16 字节。 */
    private const val HEX_LEN = 32

    fun derive(spadeA: String?): ByteArray? {
        if (spadeA.isNullOrBlank()) return null
        return keyOf(decode(spadeA))
    }

    /** 解码后的字节 -> 16 字节 key；任何一步对不上都返回 null（说明不是有效的 spade_a）。 */
    private fun keyOf(buf: ByteArray): ByteArray? {
        val bodyLen = bodyLen(buf)
        return bodyLen?.let { len ->
            val body = scramble(buf, len)
            hexValue(body[0].toInt() and 0xff)?.let { head ->
                // 首字节的值既决定 hex 片段从哪儿起，也决定它有多长
                hexToKey(body, len - head)
            }
        }
    }

    /**
     * 头部校验：由首尾三个字节算出 body 长度。
     *
     * `key0 - 0x30 < 2` 和算出来的长度越界，都说明这不是有效的 spade_a。
     */
    private fun bodyLen(buf: ByteArray): Int? {
        if (buf.size < MIN_BYTES) return null
        val key0 = (buf[0].toInt() and 0xff) xor (buf[1].toInt() and 0xff) xor (buf[2].toInt() and 0xff)
        val len = buf.size - key0 + 0x2f
        val validHead = key0 - 0x30 >= 2
        val fits = len in 2..(buf.size - 1)
        return if (validHead && fits) len else null
    }

    /** 按位变换：与下标的奇偶、以及上一个字节有关，原样照搬采集端的算法。 */
    private fun scramble(buf: ByteArray, len: Int): ByteArray {
        val body = buf.copyOfRange(1, 1 + len)
        var w11 = 0x55
        var w12 = 0xfa
        for (i in body.indices) {
            val cur = body[i].toInt() and 0xff
            val prev = if ((i and 1) == 0) w12 else w11
            if ((i and 1) == 1) w11 = cur else w12 = cur
            body[i] = (((0xeb - Integer.bitCount(i)) + (prev xor cur)) and 0xff).toByte()
        }
        return body
    }

    /** 首字节：'0'..'9' / 'a'..'z' 映射成 hex 片段的起始偏移，其余字符视为无效。 */
    private fun hexValue(c: Int): Int? = when {
        c in 0x30..0x39 -> c - 0x30
        c in 0x61..0x7a -> c - 0x57
        else -> null
    }

    /** 从 [length] 决定的位置切出 32 个 hex 字符，再转成 16 字节 key。 */
    private fun hexToKey(body: ByteArray, length: Int): ByteArray? {
        val hex = if (length < 2) "" else String(body, 1, length - 1, Charsets.US_ASCII)
        return hex.takeIf { it.length == HEX_LEN }
            ?.let { h -> ByteArray(16) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
    }

    /** FFmpeg 风格 base64：遇到非法字符按剩余位截断，不抛异常。 */
    private fun decode(s: String): ByteArray {
        val table = b64Table()
        val out = ByteArrayOutputStream()
        var i = 0
        while (i + 4 <= s.length) {
            val vals = decodeGroup(s, i, table)
            if (vals.size < 4) {
                // 尾部 "==" / "=" 那组：按已收集的位数补齐（与 FFmpeg 的 base64 一致）
                writeTail(out, vals)
                return out.toByteArray()
            }
            writeGroup(out, vals)
            i += 4
        }
        return out.toByteArray()
    }

    private fun b64Table(): IntArray {
        val table = IntArray(128) { -1 }
        B64.forEachIndexed { i, c -> table[c.code] = i }
        return table
    }

    private fun isBase64(code: Int, table: IntArray): Boolean = code < 128 && table[code] >= 0

    /** 从 [at] 起解一组 4 个字符；遇到非法字符就停下，返回已解出的那几位。 */
    private fun decodeGroup(s: String, at: Int, table: IntArray): IntArray {
        val vals = IntArray(4)
        var got = 0
        while (got < 4 && isBase64(s[at + got].code, table)) {
            vals[got] = table[s[at + got].code]
            got++
        }
        return vals.copyOf(got)
    }

    private fun writeGroup(out: ByteArrayOutputStream, vals: IntArray) {
        val v = (vals[0] shl 18) or (vals[1] shl 12) or (vals[2] shl 6) or vals[3]
        out.write((v shr 16) and 0xff)
        out.write((v shr 8) and 0xff)
        out.write(v and 0xff)
    }

    /** 尾部不足 4 个字符时，左移补位后写出应有的字节数。 */
    private fun writeTail(out: ByteArrayOutputStream, vals: IntArray) {
        var v = 0
        for (j in vals.indices) v = (v shl 6) or vals[j]
        when (vals.size) {
            3 -> {
                out.write((v shr 10) and 0xff)
                out.write((v shr 2) and 0xff)
            }
            2 -> out.write((v shr 4) and 0xff)
        }
    }
}
