package com.starveil.feature.user

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starveil.core.designsystem.token.Spacing

/**
 * 找回密码页。只渲染状态 + 上抛事件，校验与请求都在 [ResetPasswordViewModel]。
 *
 * 重置成功后走 [ResetPasswordEvent.PasswordReset] 一次性事件通知调用方回登录页，
 * 不在这里直接登录——找回密码成功后也是回到登录页。
 */
@Composable
fun ResetPasswordScreen(
    viewModel: ResetPasswordViewModel,
    onPasswordReset: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ResetPasswordEvent.PasswordReset -> onPasswordReset()
            }
        }
    }
    ResetPasswordContent(
        state = state,
        actions = ResetPasswordActions(
            onPhoneChange = viewModel::onPhoneChange,
            onCodeChange = viewModel::onCodeChange,
            onPasswordChange = viewModel::onPasswordChange,
            onPasswordConfirmChange = viewModel::onPasswordConfirmChange,
            onTogglePassword = viewModel::togglePasswordVisibility,
            onTogglePasswordConfirm = viewModel::togglePasswordConfirmVisibility,
            onSendCode = viewModel::sendCode,
            onSubmit = viewModel::submit,
            onBack = onBack,
        ),
        modifier = modifier,
    )
}

/** 找回密码页的回调集合，避免 [ResetPasswordContent] 参数越堆越多。 */
private data class ResetPasswordActions(
    val onPhoneChange: (String) -> Unit,
    val onCodeChange: (String) -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onPasswordConfirmChange: (String) -> Unit,
    val onTogglePassword: () -> Unit,
    val onTogglePasswordConfirm: () -> Unit,
    val onSendCode: () -> Unit,
    val onSubmit: () -> Unit,
    val onBack: () -> Unit,
)

@Composable
private fun ResetPasswordContent(
    state: ResetPasswordUiState,
    actions: ResetPasswordActions,
    modifier: Modifier = Modifier,
) {
    AuthScaffold(
        title = "找回密码",
        subtitle = "用注册手机号收验证码，重设后回登录页",
        onBack = actions.onBack,
        actionLabel = "返回登录",
        onAction = actions.onBack,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            AuthTextField(
                value = state.phone,
                onValueChange = actions.onPhoneChange,
                placeholder = "注册手机号",
                error = state.phoneError,
                keyboardType = KeyboardType.Phone,
            )
            CodeRow(state = state, actions = actions)
            AuthTextField(
                value = state.password,
                onValueChange = actions.onPasswordChange,
                placeholder = "新密码（至少 6 位）",
                error = state.passwordError,
                isPassword = true,
                passwordVisible = state.isPasswordVisible,
                onTogglePasswordVisibility = actions.onTogglePassword,
                keyboardType = KeyboardType.Password,
            )
            AuthTextField(
                value = state.passwordConfirm,
                onValueChange = actions.onPasswordConfirmChange,
                placeholder = "确认新密码",
                error = state.passwordConfirmError,
                isPassword = true,
                passwordVisible = state.isPasswordConfirmVisible,
                onTogglePasswordVisibility = actions.onTogglePasswordConfirm,
                keyboardType = KeyboardType.Password,
            )
            if (state.formError != null) {
                AuthFormError(message = state.formError)
            }
            AuthPrimaryButton(
                text = "重置密码",
                isSubmitting = state.isSubmitting,
                enabled = state.canSubmit,
                onClick = actions.onSubmit,
            )
        }
    }
}

/** 验证码一栏：输入框右侧内联"获取验证码"，与注册页同一套结构。 */
@Composable
private fun CodeRow(state: ResetPasswordUiState, actions: ResetPasswordActions) {
    AuthTextField(
        value = state.code,
        onValueChange = actions.onCodeChange,
        placeholder = "验证码",
        error = state.codeError,
        keyboardType = KeyboardType.Number,
        trailing = {
            AuthCodeButton(
                label = state.resendLabel,
                enabled = state.canSendCode,
                onClick = actions.onSendCode,
            )
        },
    )
}
