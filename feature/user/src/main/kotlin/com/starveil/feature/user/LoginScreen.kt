package com.starveil.feature.user

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starveil.core.designsystem.LocalPolicyLinks
import com.starveil.core.designsystem.openPolicy
import com.starveil.core.designsystem.token.Spacing

/**
 * 登录页。只渲染状态 + 上抛事件，校验与请求都在 [LoginViewModel]。
 *
 * 登录成功后走 [LoginEvent.SignedIn] 这个一次性事件通知调用方关页面，
 * 不靠 `state.isLoggedIn` 判断——旋屏重放状态会让导航跑两次。
 */
@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onSignedIn: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onRegisterClick: () -> Unit = {},
    onForgotPasswordClick: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val links = LocalPolicyLinks.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                LoginEvent.SignedIn -> onSignedIn()
            }
        }
    }
    LoginContent(
        state = state,
        actions = LoginActions(
            onUserNameChange = viewModel::onUserNameChange,
            onPasswordChange = viewModel::onPasswordChange,
            onTogglePassword = viewModel::togglePasswordVisibility,
            onAgreementChange = viewModel::onAgreementChange,
            onSubmit = viewModel::submit,
            onBack = onBack,
            onRegisterClick = onRegisterClick,
            onForgotPasswordClick = onForgotPasswordClick,
        ),
        onOpenTerms = { openPolicy(context, links.agreementUrl, "用户协议") },
        onOpenPrivacy = { openPolicy(context, links.privacyUrl, "隐私政策") },
        modifier = modifier,
    )
}

/** 登录页的回调集合，避免 [LoginContent] 参数越堆越多。 */
private data class LoginActions(
    val onUserNameChange: (String) -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onTogglePassword: () -> Unit,
    val onAgreementChange: (Boolean) -> Unit,
    val onSubmit: () -> Unit,
    val onBack: () -> Unit,
    val onRegisterClick: () -> Unit,
    val onForgotPasswordClick: () -> Unit,
)

@Composable
private fun LoginContent(
    state: LoginUiState,
    actions: LoginActions,
    onOpenTerms: () -> Unit,
    onOpenPrivacy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AuthScaffold(
        title = "星幕",
        subtitle = "登录后同步收藏、观看记录与会员权益",
        onBack = actions.onBack,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            AuthTextField(
                value = state.userName,
                onValueChange = actions.onUserNameChange,
                placeholder = "账号 / 手机号",
                error = state.userNameError,
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            AuthTextField(
                value = state.password,
                onValueChange = actions.onPasswordChange,
                placeholder = "密码",
                error = state.passwordError,
                isPassword = true,
                passwordVisible = state.isPasswordVisible,
                onTogglePasswordVisibility = actions.onTogglePassword,
                keyboardType = KeyboardType.Password,
            )
            if (state.formError != null) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                AuthFormError(message = state.formError)
            }
            Spacer(modifier = Modifier.height(Spacing.lg))
            AuthPrimaryButton(
                text = "登 录",
                isSubmitting = state.isSubmitting,
                enabled = state.canSubmit,
                onClick = actions.onSubmit,
            )
            Spacer(modifier = Modifier.height(Spacing.md))
            AuthFooterPill(
                onForgotPasswordClick = actions.onForgotPasswordClick,
                onRegisterClick = actions.onRegisterClick,
            )
            Spacer(modifier = Modifier.height(Spacing.lg))
            DividerLine(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(Spacing.md))
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
