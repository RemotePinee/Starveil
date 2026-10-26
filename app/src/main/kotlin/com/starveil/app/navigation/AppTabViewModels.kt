package com.starveil.app.navigation

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.starveil.app.AppContainer
import com.starveil.feature.pay.ARG_EPISODE_NUMBER
import com.starveil.feature.pay.ARG_PAY_MODE
import com.starveil.feature.pay.ARG_SOURCE_INDEX
import com.starveil.feature.pay.ARG_VOD_ID
import com.starveil.feature.pay.PAY_MODE_EPISODE
import com.starveil.feature.pay.PayViewModel
import com.starveil.feature.pay.payArgs

/**
 * 底栏根页面的 ViewModel，挂到整张图的起始 entry 上：切 tab 不销毁。
 *
 * `navigateSingleTop` 切走时会把离开的 tab 弹栈，
 * entry 一销毁 ViewModel 就跟着没了，切回来等于重新进页——再打一次接口、再放一次骨架屏。
 * 首页本来就在栈底所以没这个问题，其余 tab 靠这里对齐。
 *
 * 详情页 / 播放页 / 单集兑换不套这个：它们的 ViewModel 必须跟各自 entry 一起销毁。
 */
@Composable
internal inline fun <reified T : ViewModel> tabViewModel(
    nav: NavHostController,
    factory: ViewModelProvider.Factory,
): T {
    val owner = runCatching { nav.getBackStackEntry(HOME_ROUTE) }.getOrNull()
    return if (owner == null) {
        viewModel(factory = factory)
    } else {
        viewModel(viewModelStoreOwner = owner, factory = factory)
    }
}
/**
 * 会员页 ViewModel 的接线。
 *
 * 会员页是底栏 tab，切走再回来不该重新拉价目表；但同一条路由还被单集兑换复用
 * （带 `vodId` / `episode`），那两处复用同一个实例会把参数搞错，所以单集入口
 * 仍随自己的 entry 销毁。
 */
@Composable
internal fun rememberPayViewModel(
    nav: NavHostController,
    container: AppContainer,
    args: Bundle?,
): PayViewModel {
    val factory = PayViewModel.factory(
        payRepository = container.payRepository,
        startupRepository = container.startupRepository,
        userRepository = container.userRepository,
        authRepository = container.authRepository,
        args = payArgs(
            mode = args?.getString(ARG_PAY_MODE),
            vodId = args?.getInt(ARG_VOD_ID) ?: 0,
            episodeNumber = args?.getInt(ARG_EPISODE_NUMBER) ?: 0,
            sourceIndex = args?.getInt(ARG_SOURCE_INDEX) ?: 0,
        ),
    )
    return if (args?.getString(ARG_PAY_MODE) == PAY_MODE_EPISODE) {
        viewModel(factory = factory)
    } else {
        tabViewModel(nav = nav, factory = factory)
    }
}
