package com.starveil.core.data

import android.content.Context
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * 设备账号凭据（RDA，Recoverable Device Account）。
 *
 * 把 `Base64(ANDROID_ID)` 当密码，设备指纹可读、
 * 可改、可撞，等于没有密码。这里拆成两个不同性质的东西：
 *
 * - **凭据**：32 字节随机 token，用 Android Keystore 里的 AES/GCM 密钥加密后落盘，
 *   不可导出、不可从设备信息推导。登录只认它。
 * - **恢复锚**：设备指纹（SSAID）原文，只在登录请求里上报，服务端做 HMAC 后
 *   只当"找回索引"，永远不能单独换会话。
 *
 * Keystore 条目被系统清掉（换机、恢复出厂、部分卸载重装）时解密会失败，
 * 此时按"没有凭据"处理：重新生成并走一次恢复流程，服务端凭恢复锚把账号换回来。
 *
 * 登出后置 **suppressed**：用户明确退出了，下次冷启动不再自动建号/登录，
 * 直到他手动登录成功（[clearSuppressed]）。
 */
class DeviceCredentialStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 本地凭据；没有或解不开时返回 null。 */
    fun load(): String? {
        val stored = prefs.getString(KEY_TOKEN, null) ?: return null
        return runCatching { decrypt(stored) }.getOrNull()
    }

    /** 首次调用生成凭据；已有则原样返回。 */
    fun loadOrCreate(): String = load() ?: newToken().also { save(it) }

    fun save(token: String) {
        val sealed = runCatching { encrypt(token) }.getOrNull() ?: return
        prefs.edit().putString(KEY_TOKEN, sealed).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    /**
     * 恢复锚：设备指纹原文，由服务端做 HMAC 后保存。
     *
     * SSAID 在 Android 8+ 按签名与用户隔离，卸载重装不变；但个别 ROM 会返回空或
     * 固定的 `9774d56d682e549c`（2.2 时代的 bug 值），这时退回本地 UUID，
     * 至少保证同一台设备上的多次启动用同一个锚。
     */
    fun recoveryId(): String {
        val ssaid = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        // 只认"非空且不是 2.2 时代那个 bug 值"的 SSAID，其余退回本地 UUID
        val stable = ssaid.takeIf { it.isNotBlank() && it != LEGACY_BUGGY_ANDROID_ID }
            ?: prefs.getString(KEY_FALLBACK_ID, null)
        return stable ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_FALLBACK_ID, it).apply()
        }
    }

    /** 用户主动登出后置位，冷启动不再自动设备登录。 */
    val isSuppressed: Boolean
        get() = prefs.getBoolean(KEY_SUPPRESSED, false)

    fun suppress() {
        prefs.edit().putBoolean(KEY_SUPPRESSED, true).apply()
    }

    fun clearSuppressed() {
        prefs.edit().putBoolean(KEY_SUPPRESSED, false).apply()
    }

    /** 设备名：只用于后台识别，不参与任何鉴权。 */
    fun deviceName(): String =
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim().take(64)

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also { java.security.SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val payload = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(sealed: String): String {
        val payload = Base64.decode(sealed, Base64.NO_WRAP)
        val iv = payload.copyOfRange(0, IV_BYTES)
        val body = payload.copyOfRange(IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), javax.crypto.spec.GCMParameterSpec(TAG_BITS, iv))
        }
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    /** Keystore 里的 AES 密钥：系统持有，App 只能请求加解密，拿不到密钥本身。 */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "starveil_device_credential"
        const val KEY_TOKEN = "device_token"
        const val KEY_FALLBACK_ID = "fallback_recovery_id"
        const val KEY_SUPPRESSED = "device_login_suppressed"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "starveil_device_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TOKEN_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val LEGACY_BUGGY_ANDROID_ID = "9774d56d682e549c"
    }
}
