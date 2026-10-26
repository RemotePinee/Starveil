package com.starveil.app.navigation

import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.starveil.app.AppContainer
import com.starveil.core.designsystem.token.Motion

/**
 * App 的导航图：四个目的地 + 转场策略。
 *
 * 从 `StarveilNavHost` 搬出来的是"注册了哪些页面"这件事本身——它和启动配置、
 * 覆盖层、底栏都没有耦合，混在一个函数里会让那个函数的分支数一直往上涨。
 * 目的地与参数沿用原入口，页面转场统一为不透明的整幅横滑。
 */
@Composable
internal fun AppNavGraph(
    navController: NavHostController,
    container: AppContainer,
    tabOrder: List<String>,
    openAuthForm: (AuthForm) -> Unit,
) {
    val context = LocalContext.current
    NavHost(
        navController = navController,
        startDestination = HOME_ROUTE,
        modifier = Modifier.fillMaxSize(),
        // 页面统一整幅横滑，不叠加整页 alpha，避免旧视频遮罩与标题透到浅色目标页。
        enterTransition = {
            enterFor(initialState.destination.route, targetState.destination.route, tabOrder)
        },
        exitTransition = {
            exitFor(initialState.destination.route, targetState.destination.route, tabOrder)
        },
        popEnterTransition = {
            enterFor(initialState.destination.route, targetState.destination.route, tabOrder, forward = false)
        },
        popExitTransition = {
            exitFor(initialState.destination.route, targetState.destination.route, tabOrder, forward = false)
        },
    ) {
        homeDestination(navController, container)
        playerDestinations(navController, container, context)
        catalogDestinations(navController, container)
        userDestinations(navController, container, openAuthForm)
    }
}


/** 两个路由都是底栏 tab 时返回方向（true = 往右翻），否则 null。 */
private fun tabForward(from: String?, to: String?, order: List<String>): Boolean? {
    val fromIndex = order.indexOf(from)
    val toIndex = order.indexOf(to)
    return if (fromIndex >= 0 && toIndex >= 0) toIndex >= fromIndex else null
}

internal fun enterFor(
    from: String?,
    to: String?,
    order: List<String>,
    forward: Boolean = true,
): EnterTransition = tabEnter(tabForward(from, to, order) ?: forward)

internal fun exitFor(
    from: String?,
    to: String?,
    order: List<String>,
    forward: Boolean = true,
): ExitTransition = tabExit(tabForward(from, to, order) ?: forward)
/** 底栏切换进场：新页整幅从对应方向滑入，用 emphasized 曲线避免起步顿一下。 */
private fun tabEnter(forward: Boolean): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(durationMillis = Motion.TabSwitchMs, easing = Motion.emphasized),
        initialOffsetX = { width -> if (forward) width else -width },
    )

/** 旧页整幅移出，与新页共享移动边界；两页始终不透明，不靠淡出隐藏重叠区。 */
private fun tabExit(forward: Boolean): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(durationMillis = Motion.TabSwitchMs, easing = Motion.emphasized),
        targetOffsetX = { width -> if (forward) -width else width },
    )

/**
 * 二级页（详情 / 筛选）的 push 转场：新页从右整幅推入，旧页向左整幅退出。
 *
 * 与底栏切换共用同一套时长与曲线（[Motion.TabSwitchMs] / [Motion.standard]），
 * 所以整站只有一种"翻页"观感；这里只是把方向固定成 push 语义，不再靠底栏顺序推测。
 */
internal fun pushEnter(): EnterTransition = tabEnter(true)

internal fun pushExit(): ExitTransition = tabExit(true)

/** 返回：当前页向右退出，前一页从左侧推回。 */
internal fun pushPopEnter(): EnterTransition = tabEnter(false)

internal fun pushPopExit(): ExitTransition = tabExit(false)
