package com.starveil.app.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starveil.app.BuildConfig
import com.starveil.app.dialog.AppDialogKind
import com.starveil.app.dialog.DialogRequest
import com.starveil.app.dialog.DialogScheduler
import com.starveil.core.common.AppResult
import com.starveil.core.data.UpdateRepository
import com.starveil.core.designsystem.component.StarveilDialog
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.AppVersion

/**
 * 在线更新：启动时问一次后端的最新版本，比当前安装的新就交给 [DialogScheduler] 排队。
 *
 * 下载交给 [ApkInstaller]（系统 DownloadManager），这里只负责"检查 + 提示 + 收结果"。
 * 强制更新（`force = true`）时弹窗不可关闭——只能更新或退出 App。
 *
 * **排队而不是直接弹**：冷启动时隐私弹窗、公告、更新三件事同时成立的概率很高，
 * 各自 `Dialog` 直接挂上去就是三层叠在一起。语义见 `DialogScheduler`。
 */
@Composable
internal fun UpdatePromptHost(
    repository: UpdateRepository,
    scheduler: DialogScheduler,
) {
    var version by remember { mutableStateOf<AppVersion?>(null) }
    var request by remember { mutableStateOf<DialogRequest?>(null) }
    val context = LocalContext.current
    var installing by remember { mutableStateOf<AppVersion?>(null) }
    val latestInstalling by rememberUpdatedState(installing)

    LaunchedEffect(Unit) {
        val result = repository.latest()
        if (result !is AppResult.Success) return@LaunchedEffect
        // 检查更新失败不打扰用户，下次启动再问
        if (!result.value.isNewerThan(BuildConfig.VERSION_CODE)) return@LaunchedEffect
        val kind = if (result.value.force) AppDialogKind.ForceUpdate else AppDialogKind.Update
        val queued = DialogRequest(kind = kind, key = "${kind.name}:${result.value.code}")
        version = result.value
        request = queued
        scheduler.offer(queued)
    }

    // 下载完成的广播：只在本次界面存活期间收，收到后校验并安装
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val target = latestInstalling ?: return
                val finished = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                if (finished <= 0L) return
                installing = null
                ApkInstaller.install(context, finished, target.sha256)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val update = version
    val queued = request
    val current by scheduler.current.collectAsStateWithLifecycle()
    // 排队器说"还没轮到你"时什么都不画，请求留在队里等前面的弹窗处理完
    if (update == null) return
    if (queued == null || current?.key != queued.key) return

    UpdateDialog(
        version = update,
        onDismiss = {
            scheduler.complete(queued)
            version = null
        },
        onDownload = {
            val id = ApkInstaller.enqueue(context, update)
            scheduler.complete(queued)
            if (id > 0L) installing = update
            version = null
        },
    )
}

/** 更新弹窗：标题说明版本，正文是后台填的更新说明，主按钮直接开始下载。 */
@Composable
private fun UpdateDialog(version: AppVersion, onDismiss: () -> Unit, onDownload: () -> Unit) {
    StarveilDialog(onDismissRequest = if (version.force) null else onDismiss) {
        Text(
            text = if (version.force) "需要更新后才能继续使用" else "发现新版本",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "当前 ${BuildConfig.VERSION_NAME} → ${version.name}",
            style = MaterialTheme.typography.bodySmall,
            color = starveilExtraColors.textFaint,
        )
        if (version.changelog.isNotBlank()) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = version.changelog,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = CHANGELOG_LINE_HEIGHT),
                color = starveilExtraColors.textMuted,
                maxLines = CHANGELOG_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(Spacing.xl))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Spacing.control)
                .clip(AppShape.full)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onDownload),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "立即更新",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        if (!version.force) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "稍后再说",
                style = MaterialTheme.typography.bodySmall,
                color = starveilExtraColors.textFaint,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable(onClick = onDismiss),
            )
        }
    }
}

private val CHANGELOG_LINE_HEIGHT = 22.sp
private const val CHANGELOG_MAX_LINES = 6
