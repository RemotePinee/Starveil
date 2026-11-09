package com.starveil.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.starveil.app.AppContainer
import com.starveil.core.designsystem.LocalLoginPrompt
import com.starveil.core.designsystem.LoginReason

/**
 * 必须登录才能看的页面（金币 / 提现 / 会员购买）的统一门卫。
 *
 * 没登录时**不进页面**：弹登录层并退回上一页。积分与提现的数据接口本身要会话，
 * 未登录进去只会是一页空壳；会员页则根本没法下单。
 *
 * 放在单独文件里是为了让 [userDestinations] 保持在 detekt 的 40 行阈值内。
 */
@Composable
internal fun RequireLogin(
    nav: NavHostController,
    container: AppContainer,
    content: @Composable () -> Unit,
) {
    val prompt = LocalLoginPrompt.current
    val session by container.session.collectAsStateWithLifecycle()
    if (session?.isLoggedIn == true) {
        content()
        return
    }
    LaunchedEffect(Unit) {
        prompt.show(LoginReason.GENERAL)
        nav.popBackStack()
    }
}
