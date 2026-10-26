package com.starveil.feature.user

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.model.LoginMethod

/**
 * 登录方式的取舍全部来自后端 `login_modes`（[LoginChoices]）。
 *
 * 客户端不自己决定"先给什么"：默认方式决定谁在上面，另一种用"或"分隔放在下面；
 * 只开一种时另一种连分隔线都不出现。
 */
@Composable
internal fun LoginMethodSwitch(
    choices: LoginChoices,
    deviceBusy: Boolean,
    onDeviceLogin: () -> Unit,
    passwordForm: @Composable () -> Unit,
) {
    val showDevice = LoginMethod.DEVICE in choices.methods
    val showPassword = LoginMethod.PASSWORD in choices.methods
    val deviceFirst = choices.defaultMethod == LoginMethod.DEVICE
    // 设备登录始终用主按钮（白底）：它是"零输入"的最短路径，运营把它放在第一位就是主推它
    val devicePrimary = showDevice
    when {
        showDevice && showPassword && deviceFirst -> {
            DeviceLoginBlock(busy = deviceBusy, onClick = onDeviceLogin, primary = devicePrimary)
            OrDivider()
            passwordForm()
        }

        showDevice && showPassword -> {
            passwordForm()
            OrDivider()
            DeviceLoginBlock(busy = deviceBusy, onClick = onDeviceLogin, primary = false)
        }

        showDevice -> DeviceLoginBlock(busy = deviceBusy, onClick = onDeviceLogin, primary = true)
        showPassword -> passwordForm()
        else -> Text(
            text = "登录方式未配置，请联系管理员",
            style = MaterialTheme.typography.bodyMedium,
            color = starveilExtraColors.textMuted,
        )
    }
}

/** 设备一键登录：只有它一种方式时用首屏主按钮（白底胶囊），并存时降为次要样式。 */
@Composable
private fun DeviceLoginBlock(busy: Boolean, onClick: () -> Unit, primary: Boolean) {
    if (primary) {
        AuthHeroButton(text = "设备一键登录", busy = busy, onClick = onClick)
    } else {
        AuthSecondaryButton(
            text = if (busy) "登录中…" else "设备一键登录",
            onClick = onClick,
            enabled = !busy,
        )
    }
}

/** "或"分隔：一条 1dp 线中间嵌一个字，比留白更能说明两种方式并列可选。 */
@Composable
private fun OrDivider() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DividerLine(modifier = Modifier.weight(1f))
        Text(
            text = "或",
            style = MaterialTheme.typography.labelSmall,
            color = starveilExtraColors.textFaint,
            modifier = Modifier.padding(horizontal = Spacing.md),
        )
        DividerLine(modifier = Modifier.weight(1f))
    }
}

/** 1dp 分隔线：登录层里所有"条目之间"的线条都用它，避免粗细不一。 */
@Composable
internal fun DividerLine(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(Spacing.hairline)
            .background(starveilExtraColors.divider),
    )
}
