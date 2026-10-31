package com.starveil.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.starveil.app.AppContainer
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.ShortPlay
import com.starveil.feature.player.FIRST_EPISODE_NUMBER
import com.starveil.feature.shortplay.SHORT_PLAY_ROUTE
import com.starveil.feature.shortplay.ShortPlayNavigation
import com.starveil.feature.user.UserNavigation
import com.starveil.feature.player.VerticalPlayOptions
import com.starveil.feature.shortplay.ShortPlayRoute
import com.starveil.feature.shortplay.ShortPlayPresentation
import com.starveil.feature.shortplay.ShortPlayViewModel

/** 推荐与直属全集共享 origin feed 的同剧 VM；外部入口仍由自己的导航 entry 持有。 */
internal fun NavGraphBuilder.shortPlayDestination(
    nav: NavHostController,
    container: AppContainer,
) {
    composable(SHORT_PLAY_ROUTE) { entry -> ShortPlayEntry(container, nav, entry) }
}

@Composable
private fun ShortPlayEntry(container: AppContainer, nav: NavHostController, origin: NavBackStackEntry) {
    val feed: ShortPlayViewModel = viewModel(
        viewModelStoreOwner = origin,
        factory = ShortPlayViewModel.factory(
            repository = container.shortPlayRepository,
            // 分类由后台下发；老后端为空时仓库走兜底分类。
            types = container.startupRepository.cached()?.shortplayTypes.orEmpty(),
            // 搜索专用的父分类：0 = 后台没配，ViewModel 退回逐个子分类查；
            // 它同时是进短剧页的初始流（服务端按父分类查会自动带上全部子分类）。
            searchTypeId = container.startupRepository.cached()?.shortplaySearchTypeId ?: 0,
            startTypeId = container.startupRepository.cached()?.shortplaySearchTypeId ?: 0,
            // 顶部横滑条单独配的一小组分类；空则由页面退回用上面的 types。
            tabs = container.startupRepository.cached()?.shortplayTabs.orEmpty(),
        ),
    )
    val store = remember(origin) { origin.shortPlayPresentationStore() }
    val session by store.state.collectAsStateWithLifecycle()
    val frame = LocalShortPlayPresentation.current
    val expansion = if (frame.store === store) frame.expansion else store.expansion.value
    val currentEntry by nav.currentBackStackEntryAsState()
    val inlineMode = remember(origin, store, feed, nav) {
        ShortPlayInlineMode(store, feed::pinPlayback) { nav.currentBackStackEntry?.id == origin.id }
    }
    // 先注册应用级返回，播放器内层后注册的选集/推荐 sheet 优先处理。
    BackHandler(enabled = session.expanded && currentEntry?.id == origin.id) {
        if (inlineMode.isCurrent()) store.close()
    }
    val contentPadding = LocalAppContentPadding.current
    val isOriginCurrent = currentEntry?.id == origin.id
    val stablePadding = rememberStableWhileCurrent(contentPadding, isOriginCurrent)
    // 宿主扣掉的 dock 占位由短剧页自己铺回来：切 tab 那一帧的高度必须和稳态一致。
    val bleed = rememberStableWhileCurrent(LocalHostBottomInset.current, isOriginCurrent)
    CompositionLocalProvider(LocalAppContentPadding provides stablePadding) {
        ShortPlayRoute(
            viewModel = feed,
            // 退出转场期间不改测量高度：一改就会把贴底入口条推出可视区，
            // 看起来就是"那栏先消失、页面随后才滑走"。底槽留白改由前景占位保持。
            modifier = Modifier.fillMaxSize().bleedBottom(bleed),
            presentation = ShortPlayPresentation(
                seriesExpanded = session.expanded,
                interactionLocked = session.expanded,
                expansion = expansion,
                dockInset = stablePadding.calculateBottomPadding(),
            ),
            navigation = ShortPlayNavigation(
                onSearch = { nav.navigate("search") },
                onHistory = { nav.navigate(UserNavigation.HISTORY_ROUTE) },
                onFavorites = { nav.navigate(UserNavigation.FAVORITE_ROUTE) },
                onFeedback = { nav.navigate(UserNavigation.FEEDBACK_ROUTE) },
            ),
            playerContent = { play, active ->
                key(play.id) {
                    VerticalPlayEntry(container, nav, origin.shortPlayPreviewRequest(play, active, nav, inlineMode))
                }
            },
        )
    }
}

@Composable
private fun NavBackStackEntry.shortPlayPreviewRequest(
    play: ShortPlay,
    active: Boolean,
    nav: NavHostController,
    inlineMode: ShortPlayInlineMode,
): VerticalPlayRequest {
    val store = inlineMode.store
    val session by store.state.collectAsStateWithLifecycle()
    val frame = LocalShortPlayPresentation.current
    val expanded = session.expanded && session.vodId == play.id
    val expansion = if (frame.store === store) frame.expansion else store.expansion.value
    return VerticalPlayRequest(
        vodId = play.id,
        episodeNumber = FIRST_EPISODE_NUMBER,
        origin = this,
        inlineMode = inlineMode,
        initialMetadata = play,
        onBack = { if (inlineMode.isCurrent()) store.close() },
        onUpgrade = { access, episode -> nav.navigate(payRouteFor(access, play.id, episode)) },
        options = VerticalPlayOptions(
            preview = !expanded,
            active = active,
            seriesExpansion = if (expanded) expansion else 0f,
            transitioning = expanded && session.transitioning,
        ),
    )
}

/**
 * 路由一换宿主的占位就跟着变，而旧页面还在组合里往外滑：当场改高度会先跳一下。
 * 所以只在"本页是当前目的地"时接受新值，转场期间保持上一帧的。
 */
@Composable
private fun <T> rememberStableWhileCurrent(value: T, isCurrent: Boolean): T {
    var retained by remember { mutableStateOf(value) }
    SideEffect { if (isCurrent) retained = value }
    return if (isCurrent) value else retained
}

/**
 * 向下多铺 [inset]，把宿主为 dock 扣掉的那块补回来。
 *
 * 越界的一段正好落在 dock 底下被它盖住，所以视觉与"整页铺到屏幕底"一致；
 * 关键是测量高度不再随路由变化，贴底那组（标题 / 右栏 / 观看完整短剧条）不会先往上跳。
 */
private fun Modifier.bleedBottom(inset: Dp): Modifier = if (inset <= Spacing.none) {
    this
} else {
    layout { measurable, constraints ->
        val maxHeight = if (constraints.hasBoundedHeight) {
            constraints.maxHeight + inset.roundToPx()
        } else {
            constraints.maxHeight
        }
        val placeable = measurable.measure(
            Constraints(constraints.minWidth, constraints.maxWidth, 0, maxHeight),
        )
        // 只报告宿主给的尺寸：多出来那段只是画到 dock 底下，声明溢出会让居中摆放的宿主
        // 把整页往上挪半格（实测正好半个底槽，入口条与 dock 之间露出一条视频）。
        val reported = if (constraints.hasBoundedHeight) constraints.maxHeight else placeable.height
        layout(placeable.width, reported) { placeable.place(0, 0) }
    }
}
