package com.starveil.core.hongguo

import java.nio.ByteBuffer
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
        val needed = (start.skip + cipher.size + 15) / 16
        val nblocks = start.block + needed
        val counter = ByteBuffer.allocate(nblocks * 16)
        for (k in 0 until nblocks) {
            counter.put(nonce, 0, 8)
            counter.putLong(k.toLong())
        }
        val aes = Cipher.getInstance("AES/ECB/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val ks = aes.doFinal(counter.array())
        val base = start.block * 16 + start.skip
        return ByteArray(cipher.size) { i -> (cipher[i].toInt() xor ks[base + i].toInt()).toByte() }
    }
}
