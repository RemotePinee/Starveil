package com.starveil.app.navigation

import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.starveil.app.AppContainer
import com.starveil.core.common.AppResult
import com.starveil.feature.detail.detailRoute
import com.starveil.feature.player.verticalPlayRoute
import com.starveil.feature.user.HistoryScreen
import com.starveil.feature.user.HistoryViewModel
import com.starveil.feature.user.UserNavigation
import kotlinx.coroutines.launch

/**
 * 观看记录页。
 *
 * 点一条按影片类型分流：
 * - 短剧 → 竖屏播放器（`VerticalPlayRoute`），保持竖屏形态；
 * - 普通影片 → 详情页，由详情页负责断点续播。
 *
 * 列表接口带的 `type_name` 是快路径；老后端没下发时，点开时按 `vod_id` 取一次详情再判定，
 * 保证短剧不会被塞进详情页的 16:9 内嵌播放器。
 */
internal fun NavGraphBuilder.historyDestination(nav: NavHostController, container: AppContainer) {
    composable(UserNavigation.HISTORY_ROUTE) {
        val scope = rememberCoroutineScope()
        HistoryScreen(
            viewModel = viewModel(
                factory = HistoryViewModel.factory(container.continueWatchingRepository),
            ),
            onBack = { nav.popBackStack() },
            onVodClick = { item ->
                if (item.isShortPlay) {
                    nav.navigate(verticalPlayRoute(item.vodId, item.episodeNumber))
                } else {
                    scope.launch {
                        val detail = (container.vodRepository.detail(item.vodId) as? AppResult.Success)?.value
                        val route = if (detail?.isShortPlay == true) {
                            verticalPlayRoute(item.vodId, item.episodeNumber)
                        } else {
                            // 带上记录里的源与集：多源影片要回到上次那条线路，续播也靠它命中
                            detailRoute(item.vodId, item.sourceIndex, item.episodeIndex)
                        }
                        nav.navigate(route)
                    }
                }
            },
        )
    }
}
