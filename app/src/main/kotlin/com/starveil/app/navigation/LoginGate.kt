package com.starveil.app.navigation

import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.starveil.feature.user.LoginChoices
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.starveil.app.AppContainer
import com.starveil.core.designsystem.LocalLoginPrompt
import com.starveil.core.designsystem.LoginPrompt
import com.starveil.core.designsystem.LoginReason
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.feature.user.LoginHostActions
import com.starveil.feature.user.LoginSheet
import com.starveil.feature.user.LoginViewModel
import com.starveil.feature.user.LoginPromptConfig
import com.starveil.feature.user.UserNavigation

/**
 * 登录弹层宿主：把 [LocalLoginPrompt] 接到 [LoginSheet] 上。
 *
 * 只包一层，不碰路由：注册与找回密码字段多，仍然跳独立全屏页
 * （[UserNavigation.REGISTER_ROUTE] / [UserNavigation.RESET_PASSWORD_ROUTE]），
 * 登录本身留在当前页之上，用户不会丢失正在看的详情或播放进度。
 */

@Composable
internal fun LoginPromptHost(
    container: AppContainer,
    config: LoginPromptConfig,
    onOpenAuthForm: (AuthForm) -> Unit,
    content: @Composable () -> Unit,
) {
    var reason by remember { mutableStateOf<LoginReason?>(null) }
    var deviceBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prompt = remember { LoginPrompt { requested -> reason = requested } }
    CompositionLocalProvider(LocalLoginPrompt provides prompt) {
        content()
        val current = reason
        if (current != null) {
            LoginSheet(
                viewModel = config.viewModel,
                subtitle = current.subtitle,
                choices = config.choices,
                deviceBusy = deviceBusy,
                actions = LoginHostActions(
                    // 会话流推一次，锁屏与各页面的已登录分支才会跟着变
                    onSignedIn = {
                        container.onSignedIn()
                        reason = null
                    },
                    // 不清 reason：弹层留在原处，注册 / 找回密码是盖在它上面的整页表单。
                    // 清掉的话表单关掉后就回不到弹层，用户会以为"登录框被弄没了"。
                    onRegisterClick = { onOpenAuthForm(AuthForm.REGISTER) },
                    onForgotPasswordClick = { onOpenAuthForm(AuthForm.RESET) },
                    onDismiss = { reason = null },
                    onDeviceLogin = {
                        // 用户主动点：解除"登出后不自动登录"的压制再试
                        scope.launch {
                            deviceBusy = true
                            val ok = container.deviceSignInNow()
                            deviceBusy = false
                            if (ok) {
                                reason = null
                            } else {
                                // 设备登录失败必须出声：否则界面只是"点了没反应"
                                Toast.makeText(
                                    context,
                                    "设备登录失败，请改用账号或手机号登录",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                ),
            )
        }
    }
}


/** 登录原因对应的一句副标题。文案比"请先登录"更具体，用户知道登录能换来什么。 */
private val LoginReason.subtitle: String
    get() = when (this) {
        LoginReason.GENERAL -> "登录后同步收藏、观看记录与会员权益"
        LoginReason.FAVORITE -> "登录后即可收藏，换设备也能找到"
        LoginReason.COMMENT -> "登录后才能发布评论与弹幕"
        LoginReason.PLAY -> "登录后开始观看完整内容"
    }
