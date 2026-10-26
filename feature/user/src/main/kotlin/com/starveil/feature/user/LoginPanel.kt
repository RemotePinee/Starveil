package com.starveil.feature.user

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import com.starveil.core.model.LoginMethod
import com.starveil.core.model.StartupConfig
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.X
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.starveil.core.designsystem.token.IconSize

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.designsystem.LocalPolicyLinks
import com.starveil.core.designsystem.openPolicy

/**
 * 登录表单本体，登录弹层与强制登录锁屏共用。
 *
 * 与 [LoginScreen] 的差别只在容器：那边是整页骨架（自带返回箭头与页面边距），
 * 是"塞进任何面板"的内容块，边距由宿主给。字段、错误、协议仍走
 * [AuthComponents] 的同一套控件，三处登录界面不会长出第二种长相。
 *
 * 登录成功只上抛 [LoginEvent.SignedIn]：关闭弹层是宿主的事（弹层不能自己 pop 路由）。
 *
 * @param header 是否自带品牌头与标题。锁屏在外面已经有更大的品牌标题，传 false 免得重复。
 * @param onDismiss 关闭入口；为 null 表示容器不可关闭（强制登录锁屏）。
 */
@Composable
internal fun LoginPanel(
    viewModel: LoginViewModel,
    onSignedIn: () -> Unit,
    onRegisterClick: () -> Unit,
    onForgotPasswordClick: () -> Unit,
    subtitle: String = "",
    modifier: Modifier = Modifier,
    header: Boolean = true,
    onDismiss: (() -> Unit)? = null,
    choices: LoginChoices = LoginChoices(),
    onDeviceLogin: () -> Unit = {},
    deviceBusy: Boolean = false,
    /** 居中版式（强制登录锁屏）：协议行也居中。弹层保持左对齐。 */
    centered: Boolean = false,
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
    Column(modifier = modifier.fillMaxWidth()) {
        if (header) {
            LoginHeader(subtitle = subtitle, onDismiss = onDismiss)
        }
        LoginMethodSwitch(
            choices = choices,
            deviceBusy = deviceBusy,
            onDeviceLogin = onDeviceLogin,
            passwordForm = {
                LoginFormBody(
                    state = state,
                    actions = LoginFormActions(
                        onUserNameChange = viewModel::onUserNameChange,
                        onPasswordChange = viewModel::onPasswordChange,
                        onTogglePassword = viewModel::togglePasswordVisibility,
                        onAgreementChange = viewModel::onAgreementChange,
                        onSubmit = viewModel::submit,
                        onRegisterClick = onRegisterClick,
                        onForgotPasswordClick = onForgotPasswordClick,
                    ),
                    showRegister = choices.showRegister,
                    centered = centered,
                    onOpenTerms = { openPolicy(context, links.agreementUrl, "用户协议") },
                    onOpenPrivacy = { openPolicy(context, links.privacyUrl, "隐私政策") },
                )
            },
        )
    }
}


/** 品牌头：强调色短线 + 标题 + 一句副标题；可关闭时右上角给一个明确的关闭按钮。 */
@Composable
internal fun LoginHeader(
    subtitle: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandMark()
            Spacer(modifier = Modifier.weight(1f))
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = PhosphorIcons.Regular.X,
                        contentDescription = "关闭",
                        tint = starveilExtraColors.textFaint,
                        modifier = Modifier.size(IconSize.md),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text = "登录星幕",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = starveilExtraColors.textMuted,
        )
        Spacer(modifier = Modifier.height(Spacing.xl))
    }
}

/** 表单回调集合，避免 [LoginFormBody] 参数越堆越多。 */
private data class LoginFormActions(
    val onUserNameChange: (String) -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onTogglePassword: () -> Unit,
    val onAgreementChange: (Boolean) -> Unit,
    val onSubmit: () -> Unit,
    val onRegisterClick: () -> Unit,
    val onForgotPasswordClick: () -> Unit,
)

@Composable
private fun LoginFormBody(
    state: LoginUiState,
    actions: LoginFormActions,
    showRegister: Boolean = true,
    centered: Boolean = false,
    onOpenTerms: () -> Unit = {},
    onOpenPrivacy: () -> Unit = {},
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
            showRegister = showRegister,
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        AuthAgreementRow(
            checked = state.agreedToTerms,
            error = state.termsError,
            actions = AuthAgreementActions(
                onCheckedChange = actions.onAgreementChange,
                onOpenTerms = onOpenTerms,
                onOpenPrivacy = onOpenPrivacy,
            ),
            centered = centered,
        )
    }
}

/**
 * 品牌标记：一小段强调色横线 + 英文名。
 *
 * 不放 logo 图：登录层压在任意页面之上，位图在浅色海报上会糊边；
 * 一根 24×2dp 的线足够说明"这是星幕"，也不抢标题。
 */
@Composable
internal fun BrandMark(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .width(Spacing.xl)
                .height(Spacing.xxs)
                .background(starveilExtraColors.accent, AppShape.full),
        )
        Text(
            text = "STARVEIL",
            style = MaterialTheme.typography.labelSmall,
            color = starveilExtraColors.textFaint,
        )
    }
}

/**
 * 底部一枚药丸装两个出口：「忘记密码 │ 立即注册」。
 *
 * 层级靠颜色分，不靠位置分：忘记密码是次级灰、注册是主色加粗，两者共用一个
 * 形状，所以既不会被读成"两段没关系的字"，也不会因为两端对齐而散到屏幕两头。
 * 竖线只做分隔，两侧留 8dp；再宽就又读成两段了。
 *
 * 居中：与弹层/注册页共用同一枚药丸，锁屏这种居中版式里居中最稳；
 * 两端对齐会让两个出口各自贴边、中间空一片，读起来是两段没关系的字。
 *
 * 描边用主色 28% 不用分隔线色：Ink 背景上 1dp 的分隔线色等于看不见，
 * 药丸会退化成一块没有边界的浮动内边距。
 */
@Composable
internal fun AuthFooterPill(
    onForgotPasswordClick: () -> Unit,
    onRegisterClick: () -> Unit,
    showRegister: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(AppShape.full)
                .border(
                    width = Spacing.hairline,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = PILL_OUTLINE_ALPHA),
                    shape = AppShape.full,
                )
                .padding(horizontal = Spacing.md, vertical = Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillAction(
                text = "忘记密码",
                color = starveilExtraColors.textMuted,
                onClick = onForgotPasswordClick,
            )
            if (showRegister) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = Spacing.sm)
                        .size(width = Spacing.hairline, height = Spacing.md)
                        .background(starveilExtraColors.divider),
                )
                PillAction(
                    text = "立即注册",
                    color = MaterialTheme.colorScheme.primary,
                    onClick = onRegisterClick,
                    emphasized = true,
                )
            }
        }
    }
}

/** 药丸里的单个出口：各自独立可点，整高度 24dp 起，够手指点。 */
@Composable
private fun PillAction(
    text: String,
    color: Color,
    onClick: () -> Unit,
    emphasized: Boolean = false,
) {
    val style = if (emphasized) {
        MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
    } else {
        MaterialTheme.typography.bodySmall
    }
    Text(
        text = text,
        style = style,
        color = color,
        modifier = Modifier
            .clip(AppShape.small)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
    )
}

/** 药丸描边的不透明度：再高就会跟实心主按钮抢注意力。 */
private const val PILL_OUTLINE_ALPHA = 0.28f
/**
 * 登录宿主的回调集合。
 *
 * 弹层与锁屏都要这四个回调（登录成功、注册、找回密码、关闭），
 * 平铺会把参数顶到 detekt 的 5 个上限，所以收成一个数据类。
 */
data class LoginHostActions(
    val onSignedIn: () -> Unit,
    val onRegisterClick: () -> Unit,
    val onForgotPasswordClick: () -> Unit,
    val onDismiss: (() -> Unit)? = null,
    /** 设备一键登录：宿主执行静默登录（拿凭据 -> 换会话），成功后由会话流刷新界面。 */
    val onDeviceLogin: () -> Unit = {},
)

/**
 * 登录方式的取舍，全部来自后端（`login_modes` / `login_default` / `register_modes`）。
 *
 * 客户端只负责按这份配置渲染，不自己判断"该不该给设备登录"——
 * 后台取消勾选，客户端下次启动就看不到这个入口。
 */
data class LoginChoices(
    val methods: Set<LoginMethod> = StartupConfig.DEFAULT_LOGIN_METHODS,
    val defaultMethod: LoginMethod = LoginMethod.PASSWORD,
    val showRegister: Boolean = true,
) {
    companion object {
        /**
         * 后端的三个开关 → 弹层参数。缺字段一律按默认账号密码 + 可注册处理。
         *
         * 工厂放在这里而不是导航层：这是"怎么读登录配置"的知识，属于登录模块；
         * 页面与导航都只是消费方，不该各自再抄一遍那串 elvis。
         */
        fun from(startup: StartupConfig?): LoginChoices = LoginChoices(
            methods = startup?.loginMethods ?: StartupConfig.DEFAULT_LOGIN_METHODS,
            defaultMethod = startup?.loginDefaultMethod ?: LoginMethod.PASSWORD,
            showRegister = (startup?.registerMethods ?: StartupConfig.DEFAULT_REGISTER_METHODS).isNotEmpty(),
        )
    }
}
