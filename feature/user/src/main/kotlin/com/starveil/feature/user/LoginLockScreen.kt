package com.starveil.feature.user

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.Spacing

/**
 * 强制登录锁屏（服务端 `force_login = 1` 且当前没有会话时整屏显示）。
 *
 * 覆盖在整个 NavHost 之上：注册/找回仍能跳路由，登录成功后锁屏直接消失。
 * 没有底栏、没有返回箭头、没有关闭入口——这就是"必须登录才能进 App"。
 * 系统返回键不拦截：拦了会让人以为卡死，交给系统退出应用。
 *
 * **防穿透**：底层铺一张透明层吃掉空白处的点击。它和内容是兄弟节点，
 * 内容在上层，所以按钮/链接照常响应，点空白不会落到下层的海报和底栏上。
 * 不要在根节点上消费事件——那会连子级的 tap 一起取消，表现就是"按钮点了没反应"。
 */
@Composable
fun LoginLockScreen(
    viewModel: LoginViewModel,
    subtitle: String,
    actions: LoginHostActions,
    choices: LoginChoices = LoginChoices(),
    deviceBusy: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().authBackdrop()) {
        // 透明吸底层：只吃掉落在它身上的点击（也就是内容以外的空白）
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) { detectTapGestures { } },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                    .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.xxl),
        ) {
            // 上方留白比下方多一截：内容整体沉下来，不顶在屏幕正中
            Spacer(modifier = Modifier.weight(TOP_SPACE_WEIGHT))
            BrandMark()
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = "星幕",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(Spacing.xxs))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = starveilExtraColors.textMuted,
            )
            Spacer(modifier = Modifier.height(Spacing.xl))
            LoginPanel(
                viewModel = viewModel,
                onSignedIn = actions.onSignedIn,
                onRegisterClick = actions.onRegisterClick,
                onForgotPasswordClick = actions.onForgotPasswordClick,
                header = false,
                choices = choices,
                onDeviceLogin = actions.onDeviceLogin,
                deviceBusy = deviceBusy,
                centered = true,
            )
            Spacer(modifier = Modifier.weight(BOTTOM_SPACE_WEIGHT))
        }
    }
}

/** 黑洞占内容宽度的比例：再大就压到品牌标题了。 */

/** 上方弹性留白权重：比下方大，内容整体沉下来。 */
private const val TOP_SPACE_WEIGHT = 1.35f

/** 下方弹性留白权重：比上方小，内容才整体压在屏幕下三分之一。 */
private const val BOTTOM_SPACE_WEIGHT = 0.45f
