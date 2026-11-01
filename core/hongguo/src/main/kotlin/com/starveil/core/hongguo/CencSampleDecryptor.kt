package com.starveil.core.hongguo

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * CENC（ISO 23001-7）AES-CTR 解密。
 *
 * 每个 16 字节块由「IV 前 8 字节 + 块序号（大端 uint64）」组成，
 * 用 AES-128-ECB 加密得到密钥流，再与密文异或。
 *
 * 因为 CTR 是可随机访问的流密码，所以支持从任意块、任意字节偏移开始解 ——
 * 播放器发 Range 请求时只解它要的那一段，不必整集解密。
 */
object CencSampleDecryptor {

    /**
     * 起始位置：相对 sample 开头跳过多少个整块、块内再跳过多少字节。
     *
     * 打成一个类而不是两个 Int 参数，是为了让 [decryptAt] 留在参数上限之内，
     * 同时让调用点读起来是「从哪儿开始」而不是一串裸数字。
     */
    class Start(val block: Int = 0, val skip: Int = 0)

    /**
     * 线程暂存：计数器与密钥流。
     *
     * 播放时每读 64KB 就按 sample 切十几次，每次都现算现扔，等于往分配器里
     * 倒碎垃圾；按线程留着，够用就一次都不再分配。
     */
    private class Scratch {
        var counter = ByteArray(0)
        var keystream = ByteArray(0)
        var view = ByteBuffer.wrap(counter).order(ByteOrder.BIG_ENDIAN)

        /** 保证能放下 [bytes] 字节；只有变长时才换新数组。 */
        fun ensure(bytes: Int) {
            if (keystream.size >= bytes) return
            counter = ByteArray(bytes)
            keystream = ByteArray(bytes)
            view = ByteBuffer.wrap(counter).order(ByteOrder.BIG_ENDIAN)
        }
    }

    private val scratch = ThreadLocal.withInitial { Scratch() }

    /** 从 sample 开头解密。 */
    fun decrypt(key: ByteArray, nonce: ByteArray, cipher: ByteArray): ByteArray =
        decryptAt(key, nonce, cipher, Start())

    fun decryptAt(
        key: ByteArray,
        nonce: ByteArray,
        cipher: ByteArray,
        start: Start,
    ): ByteArray {
        if (cipher.isEmpty()) return ByteArray(0)
        val out = cipher.copyOf()
        decryptInPlace(key, nonce, Region(out, 0, out.size), start)
        return out
    }

    /**
     * 就地解密 [region]：明文直接写回同一段字节。
     *
     * 播放热路径原来每切一段要造 4 个数组——`copyOfRange` 出来的密文、计数器缓冲、
     * `doFinal` 的密钥流、返回的明文——再用 `arraycopy` 拷回去。每读 64KB 要切十几段，
     * 一集下来就是几十万个短命 byte[]。
     *
     * CTR 的密钥流只依赖块号，与密文无关，所以整段密钥流算完之后异或回原位，
     * 与「先拷贝再异或」逐字节相同。计数器与密钥流按线程复用，
     * 这条路径稳定后一次堆分配都没有。
     */
    fun decryptInPlace(key: ByteArray, nonce: ByteArray, region: Region, start: Start) {
        if (region.len <= 0) return
        val blocks = (start.skip + region.len + 15) / 16
        val buf = scratch.get()
        buf.ensure(blocks * 16)
        fillCounters(buf, blocks, nonce, start.block)
        val aes = Cipher.getInstance("AES/ECB/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        // 写进暂存数组的 doFinal 重载：不返回新数组，密钥流也就没有第二份拷贝
        aes.doFinal(buf.counter, 0, blocks * 16, buf.keystream, 0)
        for (i in 0 until region.len) {
            val at = region.off + i
            region.buf[at] = (region.buf[at].toInt() xor buf.keystream[start.skip + i].toInt()).toByte()
        }
    }

    /** 计数器 = IV 前 8 字节 + 大端块号；块号直接取 start.block + k，不必从 0 号块数过来。 */
    private fun fillCounters(buf: Scratch, blocks: Int, nonce: ByteArray, block: Int) {
        buf.view.clear()
        for (k in 0 until blocks) {
            buf.view.put(nonce, 0, 8)
            buf.view.putLong((block + k).toLong())
        }
    }
}
