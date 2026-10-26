package com.starveil.app.ads

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * 首次启动的广告隐私说明。
 *
 * 正文来自后端（`ads.ad_consent_text`），缺省回落到 `R.string.ad_consent_body`：
 * 隐私文案会随法规和渠道要求变化，写死在 UI 里就得为一句文案发版。
 *
 * 不做成全屏协议页：协议页在登录流程里已有，这里只承担"聚合 SDK 能否初始化"这一个
 * 决定，两个按钮够用，也不挡首页内容（点外部等同暂不）。
 */
@Composable
internal fun AdConsentDialog(
    body: String,
    onOpenPolicy: (() -> Unit)?,
    onAgree: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("关于广告") },
        text = {
            Column {
                Text(body)
                if (onOpenPolicy != null) {
                    // 隐私政策正文在站点上（后台 `privacy_url`），这里只做入口
                    TextButton(onClick = onOpenPolicy) { Text("查看隐私政策") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAgree) { Text("同意") }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { Text("暂不") }
        },
    )
}
