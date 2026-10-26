package com.starveil.feature.user

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.designsystem.LocalPolicyLinks
import com.starveil.core.designsystem.openPolicy

/**
 * 注册页。只渲染状态 + 上抛事件，校验与请求都在 [RegisterViewModel]。
 *
 * 注册成功后走 [RegisterEvent.Registered] 一次性事件通知调用方回登录页，
 * 不靠 `state` 里的布尔量判断——旋屏重放状态会让导航跑两次。
 */
@Composable
fun RegisterScreen(
    viewModel: RegisterViewModel,
    onRegistered: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val links = LocalPolicyLinks.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                RegisterEvent.Registered -> onRegistered()
            }
        }
    }
    RegisterContent(
        state = state,
        actions = RegisterActions(
            onUserNameChange = viewModel::onUserNameChange,
            onPasswordChange = viewModel::onPasswordChange,
            onPasswordConfirmChange = viewModel::onPasswordConfirmChange,
            onInviteCodeChange = viewModel::onInviteCodeChange,
            onSmsCodeChange = viewModel::onSmsCodeChange,
            onTogglePassword = viewModel::togglePasswordVisibility,
            onTogglePasswordConfirm = viewModel::togglePasswordConfirmVisibility,
            onAgreementChange = viewModel::onAgreementChange,
            onSendCode = viewModel::sendCode,
            onSubmit = viewModel::submit,
            onBack = onBack,
        ),
        onOpenTerms = { openPolicy(context, links.agreementUrl, "用户协议") },
        onOpenPrivacy = { openPolicy(context, links.privacyUrl, "隐私政策") },
        modifier = modifier,
    )
}

/** 注册页的回调集合，避免 [RegisterContent] 参数越堆越多。 */
private data class RegisterActions(
    val onUserNameChange: (String) -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onPasswordConfirmChange: (String) -> Unit,
    val onInviteCodeChange: (String) -> Unit,
    val onSmsCodeChange: (String) -> Unit,
    val onTogglePassword: () -> Unit,
    val onTogglePasswordConfirm: () -> Unit,
    val onAgreementChange: (Boolean) -> Unit,
    val onSendCode: () -> Unit,
    val onSubmit: () -> Unit,
    val onBack: () -> Unit,
)

@Composable
private fun RegisterContent(
    state: RegisterUiState,
    actions: RegisterActions,
    onOpenTerms: () -> Unit,
    onOpenPrivacy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AuthScaffold(
        title = "创建账号",
        subtitle = state.mode.subtitle,
        onBack = actions.onBack,
        actionLabel = "去登录",
        onAction = actions.onBack,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            AccountRow(state = state, actions = actions)
            // 验证码只在手机号注册时出现；账号注册不渲染这一栏，也不参与校验
            if (state.mode == RegisterMode.PHONE_SMS) {
                AuthTextField(
                    value = state.smsCode,
                    onValueChange = actions.onSmsCodeChange,
                    placeholder = "短信验证码",
                    error = state.smsCodeError,
                    keyboardType = KeyboardType.Number,
                )
            }
            AuthTextField(
                value = state.password,
                onValueChange = actions.onPasswordChange,
                placeholder = "密码（至少 6 位）",
                error = state.passwordError,
                isPassword = true,
                passwordVisible = state.isPasswordVisible,
                onTogglePasswordVisibility = actions.onTogglePassword,
                keyboardType = KeyboardType.Password,
            )
            AuthTextField(
                value = state.passwordConfirm,
                onValueChange = actions.onPasswordConfirmChange,
                placeholder = "确认密码",
                error = state.passwordConfirmError,
                isPassword = true,
                passwordVisible = state.isPasswordConfirmVisible,
                onTogglePasswordVisibility = actions.onTogglePasswordConfirm,
                keyboardType = KeyboardType.Password,
            )
            AuthTextField(
                value = state.inviteCode,
                onValueChange = actions.onInviteCodeChange,
                placeholder = "邀请码（选填）",
            )
            if (state.formError != null) {
                AuthFormError(message = state.formError)
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
            AuthPrimaryButton(
                text = "注 册",
                isSubmitting = state.isSubmitting,
                enabled = state.canSubmit,
                onClick = actions.onSubmit,
            )
            AuthAgreementRow(
                checked = state.agreedToTerms,
                error = state.termsError,
                actions = AuthAgreementActions(
                    onCheckedChange = actions.onAgreementChange,
                    onOpenTerms = onOpenTerms,
                    onOpenPrivacy = onOpenPrivacy,
                ),
            )
        }
    }
}

/**
 * 账号一栏。手机注册时把"获取验证码"塞进输入框右侧（[AuthTextField] 的 trailing），
 * 普通账号注册只留输入框。验证码本身不随注册请求上送——接口没有该字段。
 */
@Composable
private fun AccountRow(state: RegisterUiState, actions: RegisterActions) {
    AuthTextField(
        value = state.userName,
        onValueChange = actions.onUserNameChange,
        placeholder = state.accountPlaceholder,
        error = state.userNameError,
        keyboardType = if (state.showsCodeButton) KeyboardType.Phone else KeyboardType.Text,
        trailing = if (state.showsCodeButton) {
            {
                AuthCodeButton(
                    label = state.resendLabel,
                    enabled = state.canSendCode,
                    onClick = actions.onSendCode,
                )
            }
        } else {
            null
        },
    )
}
