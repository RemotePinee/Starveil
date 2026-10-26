package com.starveil.feature.user

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.starveil.core.designsystem.mirrorIconInRtl
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing

/**
 * 提现页的外壳：顶栏 + 「上次结果未知」提示条。
 *
 * 单独成文件：`WithdrawScreen.kt` 已经贴着单文件 450 行硬上限（AGENTS.md 第 2 节），
 * 这两块都不参与表单校验，拆出来不会把逻辑切碎。
 */
@Composable
internal fun WithdrawTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = PhosphorIcons.Regular.ArrowLeft,
                contentDescription = "返回",
                modifier = Modifier.mirrorIconInRtl(),
            )
        }
        Text(
            text = "金币提现",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 结果未知提示条：说明上一笔申请可能已经提交，并给出唯一的出口。
 *
 * 客户端没有提现记录接口（`WithdrawRepository` 只有 tips/apply/uploadQrcode），
 * 所谓"查询"只能由用户自己去提现记录 / 客服确认，所以这里不提供自助查询按钮，
 * 也不做"点一下就消失"的关闭——关掉提示却仍然锁着提交，用户只会以为界面坏了。
 * 要解锁必须走「我已查询，重新提交」，那次重试会带上同一个幂等键。
 */
@Composable
internal fun WithdrawPendingNotice(pending: PendingWithdraw, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShape.medium)
            .background(starveilExtraColors.surfaceElevated)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = pending.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) {
            Text("我已查询，重新提交")
        }
    }
}
