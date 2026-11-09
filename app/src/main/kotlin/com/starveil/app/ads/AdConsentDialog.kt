package com.starveil.app.ads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.starveil.core.designsystem.component.StarveilDialog
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing

/**
 * 首次启动的广告隐私说明。
 *
 * 正文来自后端（`ads.ad_consent_text`），缺省回落到 `R.string.ad_consent_body`：
 * 隐私文案随法规和渠道要求变化，写死在 UI 里就得为一句文案发版。
 *
 * **只有一个出口**：点外部、返回键都不关闭（[StarveilDialog] 传 null）。
 * 之前给过"同意 / 暂不"，但两者实际都只是"初始化或不初始化聚合 SDK"，
 * 用户看到的广告一模一样，于是变成摆设——与其留一个假选项，不如只留一个真实的决定。
 *
 * 视觉只用中性色：标题左对齐、正文两级灰、一条短分割线做分隔、
 * 主按钮实色通栏。没有品牌色光晕、没有渐变按钮。
 */
@Composable
internal fun AdConsentDialog(
    body: String,
    onOpenPolicy: (() -> Unit)?,
    onAgree: () -> Unit,
) {
    StarveilDialog(
        showClose = false,onDismissRequest = null) {
        Text(
            text = "广告与隐私",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.sm))
        // 一条短分割线：比空行更有结构感，又不抢标题
        Box(
            modifier = Modifier
                .width(Spacing.xxl)
                .height(Spacing.hairline)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = CONSENT_LINE_HEIGHT),
            color = starveilExtraColors.textMuted,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "继续即表示你已阅读并同意上述说明。",
            style = MaterialTheme.typography.bodySmall,
            color = starveilExtraColors.textFaint,
        )
        Spacer(Modifier.height(Spacing.xl))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Spacing.control)
                .clip(AppShape.full)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onAgree),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "同意并继续",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        if (onOpenPolicy != null) {
            Spacer(Modifier.height(Spacing.xs))
            TextButton(
                onClick = onOpenPolicy,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(
                    text = "查看隐私政策",
                    style = MaterialTheme.typography.bodySmall,
                    color = starveilExtraColors.textFaint,
                )
            }
        }
    }
}

/** 正文行高：13sp 正文压到 22sp 行高，长段落才不挤成一坨。 */
private val CONSENT_LINE_HEIGHT = 22.sp
