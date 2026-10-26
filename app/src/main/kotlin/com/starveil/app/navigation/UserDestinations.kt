package com.starveil.app.navigation

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.starveil.app.AppContainer
import com.starveil.feature.detail.detailRoute
import com.starveil.feature.user.FavoriteScreen
import com.starveil.feature.user.FavoriteViewModel
import com.starveil.feature.user.FeedbackScreen
import com.starveil.feature.user.FeedbackViewModel
import com.starveil.feature.user.LoginScreen
import com.starveil.feature.user.LoginViewModel
import com.starveil.feature.user.PointsScreen
import com.starveil.feature.user.PointsViewModel
import com.starveil.feature.user.RegisterScreen
import com.starveil.feature.user.RegisterViewModel
import com.starveil.feature.user.ResetPasswordScreen
import com.starveil.feature.user.ResetPasswordViewModel
import com.starveil.feature.user.UserNavigation
import com.starveil.feature.user.WithdrawScreen
import com.starveil.feature.user.WithdrawViewModel
import com.starveil.core.model.RegisterMethod
import com.starveil.feature.user.RegisterMode

/**
 * 用户中心与账号相关的目的地。
 *
 * 从 `AppDestinations.kt` 拆出来：那个文件已经贴着 400 行上限（AGENTS.md 第 2 节），
 * 再加一个账号页就会撞线。这里只放"登录态之后"的入口，首页/目录/播放仍留在原文件，
 * 导航图的拼接顺序不变——[userDestinations] 仍由 `StarveilNavHost` 调用。
 */
internal fun NavGraphBuilder.userDestinations(
    nav: NavHostController,
    container: AppContainer,
    onOpenAuthForm: (AuthForm) -> Unit,
) {
    accountDestinations(nav, container, onOpenAuthForm)
    collectionDestinations(nav, container)
}

private fun NavGraphBuilder.accountDestinations(
    nav: NavHostController,
    container: AppContainer,
    onOpenAuthForm: (AuthForm) -> Unit,
) {
    userCenterDestination(nav, container)
    // 注册 / 找回密码不在这里注册目的地：它们是登录覆盖层里的表单（见 [AuthForm]）
    loginDestination(nav, container, onOpenAuthForm)
}

private fun NavGraphBuilder.loginDestination(
    nav: NavHostController,
    container: AppContainer,
    onOpenAuthForm: (AuthForm) -> Unit,
) {
    composable(UserNavigation.LOGIN_ROUTE) {
        LoginScreen(
            viewModel = viewModel(factory = LoginViewModel.factory(container.authRepository)),
            onSignedIn = { nav.popBackStack() },
            onBack = { nav.popBackStack() },
            onRegisterClick = { onOpenAuthForm(AuthForm.REGISTER) },
            onForgotPasswordClick = { onOpenAuthForm(AuthForm.RESET) },
        )
    }
}

/**
 * 注册方式由后台 `register_modes` 决定，客户端不再写死手机注册。
 *
 * 之前这里没传 mode，[RegisterViewModel] 落回它的默认值 PHONE_SMS，所以后台只勾
 * 「账号 + 密码」时页面照样出短信验证码栏、照样要求先发码——开关形同虚设。
 * 没拿到配置时按后端默认值走（只留账号注册），不凭空开短信。
 */
internal fun registerModeOf(container: AppContainer): RegisterMode {
    val methods = container.startupRepository.cached()?.registerMethods.orEmpty()
    return if (RegisterMethod.PHONE in methods) RegisterMode.PHONE_SMS else RegisterMode.ACCOUNT
}

private fun NavGraphBuilder.collectionDestinations(nav: NavHostController, container: AppContainer) {
    composable(UserNavigation.FAVORITE_ROUTE) {
        FavoriteScreen(
            viewModel = viewModel(
                factory = FavoriteViewModel.factory(
                    favoriteRepository = container.favoriteRepository,
                    authRepository = container.authRepository,
                ),
            ),
            onBack = { nav.popBackStack() },
            onVodClick = { item -> nav.navigate(detailRoute(item.vodId)) },
            onLoginClick = { nav.navigate(UserNavigation.LOGIN_ROUTE) },
        )
    }
    profileDestination(nav, container)
    withDestinations(nav, container)
}

private fun NavGraphBuilder.withDestinations(nav: NavHostController, container: AppContainer) {
    historyDestination(nav, container)
    composable(UserNavigation.FEEDBACK_ROUTE) {
        FeedbackScreen(
            viewModel = viewModel(factory = FeedbackViewModel.factory(container.feedbackRepository)),
            onBack = { nav.popBackStack() },
        )
    }
    composable(UserNavigation.POINTS_ROUTE) {
        PointsScreen(
            viewModel = viewModel(factory = PointsViewModel.factory(container.pointsRepository)),
            onBack = { nav.popBackStack() },
        )
    }
    composable(UserNavigation.WITHDRAW_ROUTE) {
        WithdrawScreen(
            viewModel = viewModel(factory = WithdrawViewModel.factory(container.withdrawRepository)),
            onBack = { nav.popBackStack() },
        )
    }
}
