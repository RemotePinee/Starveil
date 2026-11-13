package com.starveil.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.starveil.app.AppContainer
import com.starveil.app.R
import com.starveil.core.common.AppResult
import com.starveil.core.designsystem.LocalMemberEnabled
import com.starveil.core.designsystem.LocalLoginPrompt
import com.starveil.core.designsystem.LoginPrompt
import com.starveil.core.designsystem.LoginReason
import com.starveil.core.model.Skins
import com.starveil.core.model.UserCenterVariant
import com.starveil.feature.user.UserCenterActions
import com.starveil.feature.user.UserCenterScreen
import com.starveil.feature.user.UserCenterViewModel
import com.starveil.feature.user.UserNavigation

/** 用户中心的皮肤变体：本地选择优先，服务端 `app_theme_ui` 只当默认值。 */
@Composable
private fun rememberUserCenterVariant(container: AppContainer, localSkin: String) =
    androidx.compose.runtime.produceState(UserCenterVariant.A, container, localSkin) {
        value = when (val startup = container.startupRepository.load()) {
            is AppResult.Success ->
                Skins.userCenter(Skins.resolve(startup.value.skinCode, localSkin).skin)

            is AppResult.Failure -> UserCenterVariant.A
        }
    }

/** 我的页目的地：从 [AppDestinations] 挪出来，避免那个文件顶到 400 行闸门。 */
internal fun NavGraphBuilder.userCenterDestination(nav: NavHostController, container: AppContainer) {
    composable(UserNavigation.USER_CENTER_ROUTE) {
        TabDockFrame(UserNavigation.USER_CENTER_ROUTE) {
            UserCenterRoute(nav = nav, container = container)
        }
    }
}

/**
 * 目的地内容单独成一个 @Composable：它要读 5 个 CompositionLocal 再拼一串回调，
 * 挤在 `composable {}` 里会把外层函数顶过 LongMethod 阈值。
 */
@Composable
private fun UserCenterRoute(nav: NavHostController, container: AppContainer) {
    val localSkin by container.themePreference.skinCode
        .collectAsStateWithLifecycle(initialValue = "")
    val variant by rememberUserCenterVariant(container, localSkin)
    val context = LocalContext.current
    val memberEnabled = LocalMemberEnabled.current
    val loginPrompt = LocalLoginPrompt.current
    val scope = rememberCoroutineScope()
    val userCenterViewModel = tabViewModel<UserCenterViewModel>(
        nav = nav,
        factory = UserCenterViewModel.factory(
            authRepository = container.authRepository,
            variant = variant,
            userRepository = container.userRepository,
            pointsRepository = container.pointsRepository,
            messageRepository = container.messageRepository,
            historyRepository = container.continueWatchingRepository,
            memberEnabled = memberEnabled,
            initialSession = container.sessionSnapshot(),
            initialProfile = container.userRepository.cachedProfile(),
            // 头像存的是站内相对路径，拼上域名才能请求
            apiHost = context.getString(R.string.starveil_api_host),
        ),
    )
    // 登录层不跳路由，页面不会 resume：会话一变就主动同步，否则登录完还显示"登录 / 注册"
    val session by container.session.collectAsStateWithLifecycle()
    LaunchedEffect(session?.userId) { userCenterViewModel.syncSession() }
    UserCenterScreen(
        viewModel = userCenterViewModel,
        actions = UserCenterActions(
            onLoginClick = { nav.openUserCenterEntry(container, loginPrompt) },
            // 收藏 / 设置 / 积分 / 提现 / 会员：未登录直接弹登录层，不进页面再显示"去登录"
            onEntryClick = { label ->
                if (session?.isLoggedIn == true || !entryRequiresAccount(label)) {
                    nav.handleUserEntry(label, memberEnabled, context)
                } else {
                    loginPrompt.show(LoginReason.GENERAL)
                }
            },
            onHistoryClick = { item -> nav.openContinueWatching(container, item, scope) },
            onOpenMessages = { nav.navigate(UserNavigation.MESSAGES_ROUTE) },
            onLogoutClick = {
                userCenterViewModel.logout()
                container.onSignedOut()
                nav.popBackStack()
            },
        ),
    )
}



/**
 * 「我的」页的登录入口：已登录进「个人资料」，未登录弹登录层。
 *
 * 不跳 `LOGIN_ROUTE`：用户中心的定位是"登录后还在原地"，
 * 跳独立登录页会丢掉他刚看的卡片位置。
 */
private fun NavHostController.openUserCenterEntry(container: AppContainer, prompt: LoginPrompt) {
    if (container.sessionSnapshot()?.isLoggedIn == true) {
        navigate(UserNavigation.PROFILE_ROUTE)
    } else {
        prompt.show(LoginReason.GENERAL)
    }
}
