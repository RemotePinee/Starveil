package com.starveil.app.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.starveil.app.AppContainer
import com.starveil.core.designsystem.token.Motion
import com.starveil.feature.user.RegisterScreen
import com.starveil.feature.user.RegisterViewModel
import com.starveil.feature.user.ResetPasswordScreen
import com.starveil.feature.user.ResetPasswordViewModel

/**
 * 登录覆盖层里的整页表单：注册 / 找回密码。
 *
 * 它们以前是 NavHost 的目的地。点「注册」时覆盖层先撤、路由再切页，中间那段
 * NavHost 还在显示首页——用户看到的就是"点一下先闪了个首页"。
 * 现在这两页和锁屏、弹层画在同一层：NavHost 停在原位，不存在切换，也就没有缝。
 *
 * 进场分两拍，这是不闪又能有动效的关键：
 * 1. 整层不透明底**立刻**铺满（`EnterTransition.None`），下层内容当场被盖住；
 * 2. 页面本身再做淡入 + 自下而上浮起，位移只有页高的十几分之一，够轻。
 * 如果让整层一起淡入，动画那两百毫秒里透出来的就是底下的首页。
 *
 * 返回键按"退一层覆盖"处理：表单还在就关表单，回到锁屏或用户原来那一页。
 */
@Composable
internal fun AuthFormOverlay(
    form: AuthForm?,
    container: AppContainer,
    onClose: () -> Unit,
) {
    // 退场动画期间 form 已经是 null 了，靠它记住最后一页是谁
    var lastForm by remember { mutableStateOf(AuthForm.REGISTER) }
    if (form != null) lastForm = form
    if (form == null) return

    // 必须用 Dialog 承载：登录弹层是 `ModalBottomSheet`，它跑在自己的 Dialog 窗口里，
    // 层级高于 Activity 的 Compose 树。画在主树上的覆盖层会被那条弹层盖住——
    // 用户看到的就是"注册页在弹窗下面一层"。放进独立窗口之后才是真正的上一层。
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 不透明底：动画全程挡着下层，露出一点首页都算 bug
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
            )
            var entered by remember(lastForm) { mutableStateOf(false) }
            LaunchedEffect(lastForm) { entered = true }
            AnimatedVisibility(
                visible = entered,
                enter = fadeIn(animationSpec = tween(durationMillis = FORM_ENTER_MS)) +
                    slideInVertically(
                        animationSpec = tween(
                            durationMillis = FORM_ENTER_MS,
                            easing = Motion.standard,
                        ),
                        initialOffsetY = { height -> height / FORM_SLIDE_FRACTION },
                    ),
                exit = ExitTransition.None,
            ) {
                when (lastForm) {
                    AuthForm.REGISTER -> RegisterScreen(
                        viewModel = viewModel(
                            factory = RegisterViewModel.factory(
                                authRepository = container.authRepository,
                                mode = registerModeOf(container),
                            ),
                        ),
                        // 注册成功后回登录：此时还没有会话，让用户自己用新账号登一次
                        onRegistered = onClose,
                        onBack = onClose,
                        modifier = Modifier.fillMaxSize(),
                    )

                    AuthForm.RESET -> ResetPasswordScreen(
                        viewModel = viewModel(
                            factory = ResetPasswordViewModel.factory(container.authRepository),
                        ),
                        onPasswordReset = onClose,
                        onBack = onClose,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** 页面浮起的时长：短于一次 tab 切换，读起来是"立刻到位"而不是等动画。 */
private const val FORM_ENTER_MS = 260


/** 起始下移量占页高的比例：太大会像抽屉，太小看不出在动。 */
private const val FORM_SLIDE_FRACTION = 14
