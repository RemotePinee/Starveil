package com.starveil.feature.user

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing
import androidx.compose.runtime.Immutable

/**
 * 可关闭的登录弹层（服务端未开强制登录时用）。
 *
 * 只是 [ModalBottomSheet] 容器 + [LoginPanel]，与选集/倍速等弹层同一套参数
 * （`containerColor = surface`、[AppShape.large]），用户看到的是同一种弹层，不是第三种。
 *
 * 内容可滚动并让开输入法：小屏上一弹键盘，登录按钮和协议行必须还能点到。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginSheet(
    viewModel: LoginViewModel,
    subtitle: String,
    actions: LoginHostActions,
    choices: LoginChoices = LoginChoices(),
    deviceBusy: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { actions.onDismiss?.invoke() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = AppShape.sheetTopLarge,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = Spacing.pageHorizontal)
                // 底部留出整段空白：协议行是面板最后一项，下面不留白会显得"贴着屏幕边"
                .padding(bottom = Spacing.xxl),
        ) {
            LoginPanel(
                viewModel = viewModel,
                subtitle = subtitle,
                onSignedIn = actions.onSignedIn,
                onRegisterClick = actions.onRegisterClick,
                onForgotPasswordClick = actions.onForgotPasswordClick,
                // 不传 onDismiss：弹层本身能下滑 / 点遮罩关闭，右上角再放一个 × 是重复入口，
                // 而且和强制登录锁屏那套版式（没有 ×）对不上
                onDismiss = null,
                choices = choices,
                onDeviceLogin = actions.onDeviceLogin,
                deviceBusy = deviceBusy,
                // 协议行居中：弹层与锁屏现在是同一套版式，只差容器（弹层从底部升起）
                centered = true,
            )
        }
    }
}

/**
 * 登录弹层宿主的两块不变参数：表单 VM 与可选登录方式。
 *
 * 与 [LoginSheet] 同处：一个是弹层本体，一个是弹层需要的固定参数；
 * 收成对象是为了压住宿主的参数个数（detekt 阈值 5），不是为了复用——
 * `StarveilNavHost` 是唯一调用方。
 */
@Immutable
data class LoginPromptConfig(
    val viewModel: LoginViewModel,
    val choices: LoginChoices,
)
