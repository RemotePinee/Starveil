package com.starveil.feature.user

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream

/** 读相册的缓冲块：8KB 摊平每次系统调用的开销，又不会自己占多少内存。 */
private const val READ_BUFFER_BYTES = 8 * 1024

/**
 * 读相册选中的图片字节，超过 [maxBytes] 立刻返回 null。
 *
 * **必须在 IO 线程调用**：`ContentResolver` 读的是另一个进程的文件，
 * 放在相册回调（主线程）里会卡住界面。
 *
 * 用"边读边限长"而不是 `readBytes()` 再比大小——后者会先把整张图读进内存，
 * 相册里几十 MB 的原图足够直接把进程撑爆。这两条路径只拿字节上传、不 decode，
 * 所以不需要缩放位图；真正会被解码的是海报模板，那里的尺寸保护在
 * `SharePoster.loadTemplate`。
 */
internal fun readPickedBytes(context: Context, uri: Uri, maxBytes: Int): ByteArray? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (out.size() <= maxBytes) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        if (out.size() == 0 || out.size() > maxBytes) null else out.toByteArray()
    }
}.getOrNull()
