package com.starveil.core.hongguo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 用线上真实样本锁定算法：spade_a 派生 + CENC-AES-CTR 解密。
 * 样本取自红果 vid=7629639568660106302 的 480p（14.4MB），
 * 明文是 HEVC 的 VPS（长度前缀 0x1d + NAL 头 0x40），能直接看出解对了。
 */
class SpadeKeyTest {

    private val spadeA = "krwcy0yMBcFVti37Y7ce51KuGtVLrDb9SYQH/nuHKsh7sTOzsw=="

    @Test
    fun deriveKeyMatchesRealSample() {
        val key = SpadeKey.derive(spadeA)
        assertNotNull(key)
        assertEquals("3a91268aa665e8331d9be9fb6bca867c", hex(key!!))
    }

    @Test
    fun deriveKeyRejectsBadInput() {
        assertNull(SpadeKey.derive(null))
        assertNull(SpadeKey.derive(""))
        assertNull(SpadeKey.derive("!!!not-base64!!!"))
    }

    /** 视频轨第一个 sample：明文是 00 00 00 1d 40 01（长度前缀 + HEVC VPS）。 */
    @Test
    fun decryptVideoSampleMatchesRealPair() {
        val key = SpadeKey.derive(spadeA)!!
        val iv = bytes("da9d5b88146dec45")
        val cipher = bytes("c66b8d1d00bedf579684f10a1e528df6")
        val plain = CencSampleDecryptor.decrypt(key, iv, cipher)
        assertArrayEquals(bytes("0000001d40010c02ffff016000000300"), plain)
    }

    /** 从 sample 中间起解，结果必须和整体解出的同一段一致（Range 请求靠这个）。 */
    @Test
    fun decryptAtSupportsArbitraryOffset() {
        val key = SpadeKey.derive(spadeA)!!
        val iv = bytes("da9d5b88146dec45")
        val cipher = bytes("c66b8d1d00bedf579684f10a1e528df6") + bytes("118e08820019d6b917eba50775fcff3a")
        val whole = CencSampleDecryptor.decrypt(key, iv, cipher)

        val from = 5
        val start = CencSampleDecryptor.Start(block = 0, skip = from)
        val tail = CencSampleDecryptor.decryptAt(key, iv, cipher.copyOfRange(from, from + 20), start)
        assertArrayEquals(whole.copyOfRange(from, from + 20), tail)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun bytes(s: String) =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
