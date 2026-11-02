package com.starveil.feature.user

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing

/**
 * 「我的」页的三个通用条：分享确认弹窗、同步失败提示、退出登录。
 *
 * 从 `UserCenterScreen` 拆出来只为压文件行数；它们跟页面结构无关，
 * 换布局时不用跟着动。
 */

/** 分享奖励确认：chooser 没有结果回调，"回到前台"不等于"发出去了"，问一句再发。 */
@Composable
internal fun ShareRewardDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // 圆角走 M3 默认：上一版把它收到 12dp，用户没要求改圆角，已回滚。
        // 扁平化只保留"不叠 tonal 提亮"，弹层层级由 containerColor
        // （surfaceContainerHigh `#26272F`）与窗外遮罩表达
        tonalElevation = Spacing.none,
        title = { Text("分享完成了吗？") },
        text = { Text("确认已经把海报发出去，就领取今日分享积分。") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("已分享，领取") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("还没发") }
        },
    )
}

@Composable
internal fun SyncErrorBanner(message: String, onRetry: () -> Unit) {
    Surface(
        shape = AppShape.medium,
        color = starveilExtraColors.surfaceCard,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(WEIGHT_FULL),
            )
            TextButton(onClick = onRetry) {
                Text(text = "重试", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
internal fun SignOutRow(onLogoutClick: () -> Unit) {
    Surface(
        shape = AppShape.extraLarge,
        color = starveilExtraColors.surfaceCard,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onLogoutClick),
    ) {
        Text(
            text = "退出登录",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.lg),
        )
    }
}

private const val WEIGHT_FULL = 1f
