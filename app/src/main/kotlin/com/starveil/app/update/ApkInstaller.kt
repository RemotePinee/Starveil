package com.starveil.app.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import com.starveil.core.model.AppVersion
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * APK 下载与安装。
 *
 * 用系统 `DownloadManager` 而不是自己写下载：App 切后台它继续跑、自带通知栏进度，
 * 省掉一整套前台服务与通知权限的样板。
 *
 * 落盘用 **external-files/Documents**，不需要任何存储权限（targetSdk 36 下申请外部
 * 存储已经不现实）；安装通过 DownloadManager 自己的 content Uri 拉起系统安装器，
 * 不额外暴露 FileProvider 路径。
 */
object ApkInstaller {

    private const val APK_NAME = "starveil-update.apk"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val BUFFER_SIZE = 8 * 1024

    /** 入队下载；返回 downloadId，-1 表示地址非法或入队失败。 */
    fun enqueue(context: Context, version: AppVersion): Long {
        // 明文地址直接拒绝：更新包是最典型的中间人目标，这里不开洞
        if (!version.url.startsWith("https://")) return -1L
        val request = DownloadManager.Request(Uri.parse(version.url))
            .setTitle("Starveil ${version.name}".trim())
            .setMimeType(APK_MIME)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_NAME)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
        return runCatching {
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        }.getOrDefault(-1L)
    }

    /**
     * 安装已下载完成的包。
     *
     * 后端给了 sha256 才校验；校验不过直接放弃——宁可不更新，也不装一个来源不明的包。
     */
    fun install(context: Context, downloadId: Long, expectedSha256: String) {
        if (downloadId <= 0L) return
        // 后端给了校验值才校验；校验不过宁可不更新，也不装来源不明的包
        val verified = expectedSha256.isBlank() || sha256Matches(context, downloadId, expectedSha256)
        if (!verified) return
        launchInstaller(context, downloadId)
    }

    /** 拉起系统安装器。拿不到 content Uri 说明下载没落盘，直接放弃。 */
    private fun launchInstaller(context: Context, downloadId: Long) {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = manager.getUriForDownloadedFile(downloadId) ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** 下载完成的广播只给 id，要和入队时记下的那个比对，避免误装别的任务。 */
    fun isOurs(ourId: Long, finishedId: Long): Boolean = ourId > 0L && ourId == finishedId

    private fun sha256Matches(context: Context, downloadId: Long, expected: String): Boolean =
        runCatching {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val digest = MessageDigest.getInstance("SHA-256")
            val descriptor = manager.openDownloadedFile(downloadId)
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
                .equals(expected.trim(), ignoreCase = true)
        }.getOrDefault(false)
}
