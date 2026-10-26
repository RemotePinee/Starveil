package com.starveil.feature.user

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 缩略图边长 64dp：由间距令牌拼出，页面不写裸 dp（AGENTS.md 第 3 节）。 */
private val QrcodeThumbSize = Spacing.xxl * 2

/** 上传前先拦一道体积：服务端有上限，超了必然白跑一次请求。 */
private const val QRCODE_MAX_BYTES = 5 * 1024 * 1024
private const val QRCODE_FALLBACK_MIME = "image/jpeg"

/**
 * 选中的收款码：字节用于上传，[uri] 只给页面画缩略图。
 *
 * 带字节而不是带 uri 上传：读相册要 `ContentResolver`，它只在页面这层，
 * 仓库构造只拿得到 `StarveilApi`（见 `WithdrawRepository.uploadQrcode` 注释），
 * 与头像上传（`ProfileScreen.readAvatar`）是同一套路。
 */
data class QrcodePick(
    val uri: String,
    val bytes: ByteArray,
    val fileName: String,
    val mime: String,
)

/** 收款码区块的回调集合，避免 Composable 参数越堆越多。 */
internal data class QrcodeActions(
    val onPicked: (QrcodePick) -> Unit,
    val onRemove: () -> Unit,
    val onPickFailed: () -> Unit,
)

/**
 * 收款码（选填）：没传时给一个相册入口，传完显示缩略图 + 重新上传 / 移除。
 *
 * 上传中把所有按钮禁掉：连点会并发上传，最终落哪个路径不可控，
 * 而提交只认一个 [qrcodePath]。
 */
@Composable
internal fun WithdrawQrcodeSection(
    qrcodePath: String,
    previewUri: String,
    uploading: Boolean,
    actions: QrcodeActions,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) {
            // 用户在系统相册里返回，不算失败，别弹提示
            return@rememberLauncherForActivityResult
        }
        // 读流是磁盘 IO：相册回调在主线程，必须挪到 IO；读完后仍在主线程回调页面
        scope.launch {
            val pick = withContext(Dispatchers.IO) { readQrcode(context, uri) }
            if (pick == null) actions.onPickFailed() else actions.onPicked(pick)
        }
    }
    val launchPicker = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = "收款码（选填）",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (qrcodePath.isBlank()) {
            QrcodePickButton(uploading = uploading, onPick = launchPicker)
        } else {
            QrcodeUploadedRow(
                previewUri = previewUri,
                uploading = uploading,
                onRepick = launchPicker,
                onRemove = actions.onRemove,
            )
        }
        Text(
            text = "上传支付宝 / 微信收款码截图，审核时核对收款人",
            style = MaterialTheme.typography.bodySmall,
            color = starveilExtraColors.textMuted,
        )
    }
}

/** 未上传态：上传中用进度条顶掉按钮，从入口上杜绝重复提交。 */
@Composable
private fun QrcodePickButton(uploading: Boolean, onPick: () -> Unit) {
    if (!uploading) {
        OutlinedButton(onClick = onPick, shape = AppShape.small) { Text("上传收款码") }
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(IconSize.sm))
        Text(
            text = "收款码上传中…",
            style = MaterialTheme.typography.bodySmall,
            color = starveilExtraColors.textMuted,
        )
    }
}

/** 已上传态：缩略图 + 重新上传 / 移除；预览用本地 uri，不占用一次图片请求。 */
@Composable
private fun QrcodeUploadedRow(
    previewUri: String,
    uploading: Boolean,
    onRepick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        AsyncImage(
            model = previewUri,
            contentDescription = "收款码预览",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(QrcodeThumbSize)
                .clip(AppShape.small)
                .background(starveilExtraColors.surfaceCard),
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.none)) {
            Text(
                text = if (uploading) "上传中…" else "已上传",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                TextButton(onClick = onRepick, enabled = !uploading) { Text("重新上传") }
                TextButton(onClick = onRemove, enabled = !uploading) { Text("移除") }
            }
        }
    }
}

/** 读相册里选中的图；读不出来返回 null，由页面提示换一张。必须在 IO 线程调用。 */
private fun readQrcode(context: Context, uri: Uri): QrcodePick? = runCatching {
    val bytes = readPickedBytes(context, uri, QRCODE_MAX_BYTES) ?: return null
    val mime = context.contentResolver.getType(uri)
        ?.takeIf { type -> type.startsWith("image/") }
        ?: QRCODE_FALLBACK_MIME
    QrcodePick(uri = uri.toString(), bytes = bytes, fileName = fileNameOf(uri, mime), mime = mime)
}.getOrNull()

/** 相册给的 lastPathSegment 常常没有扩展名，按 mime 补一个，服务端按扩展名落盘。 */
private fun fileNameOf(uri: Uri, mime: String): String {
    val segment = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    if (segment.contains('.')) return segment
    return when (mime) {
        "image/png" -> "qrcode.png"
        "image/webp" -> "qrcode.webp"
        else -> "qrcode.jpg"
    }
}
