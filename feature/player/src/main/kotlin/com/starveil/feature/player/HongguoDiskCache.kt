package com.starveil.feature.player

import android.content.Context
import com.starveil.core.hongguo.Mp4SampleTable
import com.starveil.core.hongguo.SampleTableCodec
import java.io.File

/**
 * sample 表与密钥的磁盘缓存。
 *
 * 同一集的 sample 表、spade_a 派生的密钥都不会变，存下来之后重播就只剩读文件，
 * 不必再为了解析 moov 去下载 1MB 头部（那是首帧前最耗时的一步）。
 *
 * 目录是 cacheDir 下的 hongguo/：系统空间紧张时可以安全清掉，清掉只是回到慢路径。
 */
internal object HongguoDiskCache {

    private const val DIR = "hongguo"

    /** 密钥固定 16 字节（AES-128）；长度不对说明文件被写坏了。 */
    private const val KEY_BYTES = 16L

    /**
     * 缓存有效期。
     *
     * 缓存 key 只有 series/episode，不含密钥——服务端换过 spade_a 之后，
     * 旧密钥对应的表还在，命中就直接返回，用旧密钥解新分片只会黑屏。
     * 加了 TTL 之后最多滞后这么久就会重新拉一次材料。
     */
    private const val TTL_MS = 30 * 60 * 1000L

    /** 存的是一条 sample 表 + 对应的 16 字节密钥。 */
    class Entry(val key: ByteArray, val table: Mp4SampleTable)

    private fun dir(context: Context): File =
        File(context.cacheDir, DIR).apply { if (!exists()) mkdirs() }

    private fun tableFile(context: Context, tag: String): File = File(dir(context), "$tag.tbl")
    private fun keyFile(context: Context, tag: String): File = File(dir(context), "$tag.key")

    fun load(context: Context, tag: String): Entry? = runCatching {
        val tf = tableFile(context, tag)
        val kf = keyFile(context, tag)
        // 两个文件都在、且 key 长度是 16 字节，才是一次完整的写入
        val present = tf.exists() && kf.exists() && kf.length() == KEY_BYTES
        if (!present) return null
        if (System.currentTimeMillis() - kf.lastModified() > TTL_MS) {
            tf.delete()
            kf.delete()
            return null
        }
        val table = SampleTableCodec.decode(tf.readBytes()) ?: return null
        Entry(kf.readBytes(), table)
    }.getOrNull()

    fun save(context: Context, tag: String, key: ByteArray, table: Mp4SampleTable) = runCatching {
        val kf = keyFile(context, tag)
        // tag 是 "<剧>/<集>"，中间带一级目录：父目录不先建出来，writeBytes 会抛
        // FileNotFoundException，下面的 runCatching 又把它吞了——缓存会静默地永远写不进去。
        kf.parentFile?.mkdirs()
        kf.writeBytes(key)
        tableFile(context, tag).writeBytes(SampleTableCodec.encode(table))
        Unit
    }.isSuccess

    /** 只保留最近的若干集，避免短剧刷几千集把缓存目录撑爆。 */
    fun trim(context: Context, keep: Int = 40) {
        val files = dir(context).listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(keep * 2).forEach { it.delete() }      // 每集两个文件（tbl + key）
    }
}
