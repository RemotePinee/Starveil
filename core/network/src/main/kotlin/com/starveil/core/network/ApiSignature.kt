package com.starveil.core.network

import java.security.MessageDigest

/**
 * 接口签名参数生成。
 *
 * 算法：md5(year:hour:year:minute:secret)。
 * 注意 year 出现两次，这是的原样行为，不是笔误——改动会让签名与既有
 * 解析站不兼容。
 *
 * 重要：后端对 apikey/keytime **不做任何校验**（详见 docs/security.md），
 * 因此这两个参数不是安全机制，只是兼容既有契约。不要在此基础上做业务防刷。
 */
class ApiSignature(private val secret: String) {

    fun current(): Signature = buildSignature(System.currentTimeMillis() / 1000)

    fun at(epochSeconds: Long): Signature = buildSignature(epochSeconds)

    private fun buildSignature(epochSeconds: Long): Signature {
        val time = java.util.Calendar.getInstance().apply { timeInMillis = epochSeconds * 1000 }
        val year = time.get(java.util.Calendar.YEAR).toString()
        val hour = time.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val minute = time.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
        val raw = "$year:$hour:$year:$minute:$secret"
        return Signature(apiKey = raw.md5(), keyTime = epochSeconds.toString())
    }

    data class Signature(val apiKey: String, val keyTime: String)
}

private fun String.md5(): String = MessageDigest.getInstance("MD5")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
