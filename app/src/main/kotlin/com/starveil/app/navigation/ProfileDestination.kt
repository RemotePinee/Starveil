package com.starveil.app.navigation

import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.starveil.app.AppContainer
import com.starveil.app.BuildConfig
import com.starveil.app.R
import com.starveil.core.designsystem.LocalLoginPrompt
import com.starveil.core.designsystem.LoginReason
import com.starveil.feature.user.ProfileScreen
import com.starveil.feature.user.ProfileViewModel
import com.starveil.feature.user.UserNavigation

/** 账号与设置页：域名与版本号在这里注入，feature 模块拿不到 app 的 BuildConfig 与资源。 */
internal fun NavGraphBuilder.profileDestination(nav: NavHostController, container: AppContainer) {
    composable(UserNavigation.PROFILE_ROUTE) {
        val context = LocalContext.current
        val loginPrompt = LocalLoginPrompt.current
        ProfileScreen(
            viewModel = viewModel(
                factory = ProfileViewModel.factory(
                    userRepository = container.userRepository,
                    authRepository = container.authRepository,
                    // 头像存的是站内相对路径，拼上域名才能请求
                    apiHost = context.getString(R.string.starveil_api_host),
                    versionName = BuildConfig.VERSION_NAME,
                    initialProfile = container.userRepository.cachedProfile(),
                ),
            ),
            onBack = { nav.popBackStack() },
            onLoginClick = { loginPrompt.show(LoginReason.GENERAL) },
            onSignedOut = {
                // 登出要压住"设备自动登录"，否则下次冷启动又静默建号；
                // 强制登录开启时，会话清空会立刻回到锁屏
                container.onSignedOut()
                nav.popBackStack()
            },
        )
    }
}
