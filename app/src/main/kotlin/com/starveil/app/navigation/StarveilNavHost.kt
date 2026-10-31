package com.starveil.app.navigation
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import com.starveil.feature.user.LoginChoices
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.starveil.app.AppContainer
import com.starveil.app.SplashSkinStore
import com.starveil.app.ads.AdHost
import com.starveil.app.ads.AdRuntime
import com.starveil.app.ads.AdConsentGateWhenReady
import com.starveil.app.ads.AdSplashGate
import com.starveil.core.common.AppResult
import com.starveil.core.common.ad.AdPlacements
import com.starveil.core.common.ad.AdPlacement
import com.starveil.core.common.DetailSections
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.LocalMemberEnabled
import com.starveil.core.designsystem.LocalPolicyLinks
import com.starveil.core.designsystem.PolicyLinks
import com.starveil.core.designsystem.LocalDetailSections
import com.starveil.core.model.StartupConfig
import com.starveil.core.model.LoginMethod
import com.starveil.core.designsystem.LocalForceLogin
import com.starveil.feature.user.LoginHostActions
import com.starveil.feature.user.LoginLockScreen
import com.starveil.feature.user.LoginViewModel
import com.starveil.feature.user.LoginPromptConfig
import com.starveil.feature.user.UserNavigation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.withTimeoutOrNull
import com.starveil.feature.player.PlayerEvent
import com.starveil.feature.player.VERTICAL_PLAY_ROUTE_PATTERN
import androidx.compose.material3.ScaffoldDefaults
import com.starveil.feature.shortplay.SHORT_PLAY_ROUTE
import androidx.compose.foundation.layout.WindowInsets
import com.starveil.core.designsystem.token.Motion
import androidx.compose.animation.fadeOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import com.starveil.core.designsystem.interaction.rememberHaptics
import com.starveil.core.designsystem.interaction.HapticKind

/**
 * 导航图。每个页面在这里注册，路由常量来自各 feature 的 `*Navigation.kt`。
 *
 * 底部导航由服务端 `bottom_tabs` 决定：可见项、顺序、文案、图标都来自配置，
 * 配置缺失时退回默认五栏（首页 / 专题或直播 / 榜单 / 会员 / 我的）。
 * 隐藏项只是不渲染，对应路由仍全部注册，深链与首页快捷入口照常可达。
 *
 * 目的地定义拆在 `AppDestinations.kt`：单文件超 400 行会被 `verifyFileLength` 拦，
 * 且单个 NavHost 函数复杂度会超过 detekt 阈值。底栏项与图标映射在 `BottomTabSpec.kt`。
 */
internal const val HOME_ROUTE = "home"

/**
 * 启动配置的加载状态。
 *
 * 用三态而不是 `StartupConfig?`：`null` 同时表示"还没回来"和"拉取失败"，
 * 强制登录会因此晚一拍生效——首页先出来一帧，再被锁屏盖住。
 */
internal sealed interface StartupLoad {
    data object Loading : StartupLoad
    data class Ready(val config: StartupConfig?) : StartupLoad
}

/** 强制登录锁屏的副标题：说清楚登录换到什么，而不是干巴巴一句"请登录"。 */
private const val FORCE_LOGIN_SUBTITLE = "登录后开始观看"

/**
 * 整页表单路由：登录 / 注册 / 找回密码。
 * 这些页面挂在 NavHost 里，但语义是"中断式的整页流程"，不是 tab 内容；
 * 底栏在它们上面不渲染（见 bottomBar 条件）。
 */
internal val FULLSCREEN_FORM_ROUTES = setOf(
    UserNavigation.LOGIN_ROUTE,
    // 竖屏播放是整页沉浸：底栏在它上面会盖住选集按钮和进度条（红果同样隐藏）
    VERTICAL_PLAY_ROUTE_PATTERN,
)

@Composable
fun StarveilNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val context = LocalContext.current

    // 启动配置只读一次（三态：加载中 / 有配置 / 拉取失败）
    val startupState = rememberStartupLoad(container)
    val startup = (startupState as? StartupLoad.Ready)?.config
    // 配置到手就存一份同步快照：下次冷启动在它回来之前，锁屏、登录方式、窗口底色
    // 全按这份快照先排好，不再出现"先默认、再跳成后台配置"的闪动
    LaunchedEffect(startup) {
        if (startup != null) SplashSkinStore.writeStartup(context, startup)
    }
    // 首屏预取：冷启动配置一到就把剧场第一页写进快照。用户点短剧 tab 时
    LaunchedEffect(startup) { prefetchShortPlay(container, startup) }
    val session by container.session.collectAsStateWithLifecycle()
    // 配置还没回来时先用上次的快照决定锁不锁屏：直接渲染内容不等于放弃强制登录，
    // 否则先闪一帧游客首页再盖锁屏，比等一层纯色底更难看。
    val forceLogin = startup?.forceLogin ?: SplashSkinStore.readForceLogin(context)
    // 用 isLoggedIn 而不是 `session == null`：jar 为空时 Session.fromCookies 仍会返回一个
    // 未登录对象（userId 为空），`session == null` 永远为 false，强制登录就永远不锁
    val loggedIn = session?.isLoggedIn == true
    // 登录覆盖层现在只有一个入口是路由页（登录页本身）；注册与找回密码是覆盖层里的表单。
    val locked = forceLogin && !loggedIn
    // 覆盖层里的整页表单（注册 / 找回密码）：与锁屏、弹层同处一层，不参与 NavHost 切换
    var authForm by remember { mutableStateOf<AuthForm?>(null) }
    val openAuthForm: (AuthForm) -> Unit = { authForm = it }
    val closeAuthForm: () -> Unit = { authForm = null }
    // 登录表单在整个 App 只有一份状态：锁屏与弹层共用，切换容器时不丢已输入的账号
    val loginViewModel: LoginViewModel = viewModel(
        factory = LoginViewModel.factory(container.authRepository),
    )
    // 登录方式完全由后端决定：勾了哪些就渲染哪些，默认哪种就把它放上面
    // 配置没回来时用上次的快照排布：不然先按默认排一遍（密码优先），
    // 配置一到设备登录就跳到上面去，看起来就是"按钮后冒出来"。
    val loginChoices = rememberLoginChoices(startup, context)
    // 锁屏与弹层共用同一个"设备登录中"状态，避免两处各自转圈
    var deviceBusy by remember { mutableStateOf(false) }
    val loginScope = rememberCoroutineScope()
    val onDeviceLogin: () -> Unit = {
        loginScope.launch {
            deviceBusy = true
            container.deviceSignInNow(notifyFailure = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() })
            deviceBusy = false
        }
    }
    val tabs = remember(startup) { bottomTabSpecs(startup) }
    // 底栏顺序：tab 之间滑动的方向按它在底栏里的位置决定
    val tabOrder = remember(tabs) { tabs.map { it.selectedRoute } }
    // 会员关闭只隐藏售卖入口；历史会员权益由服务端按到期时间放行，客户端不参与判断
    val memberEnabled = startup?.memberEnabled ?: true
    // 广告位来自启动配置：五个位一起下发，任一为空就是"不展示"，页面不需要再判开关。
    // 配置没回来时用上次存下的直投开屏素材兜底：首帧就把遮罩画上，否则首页先露一帧、
    // 广告再盖上来，看起来就是"闪一下首页"。首次安装没有历史素材，仍然只能等配置。
    val ads = startup?.ads ?: remember {
        AdPlacements.NONE.copy(splash = AdSplashGate.splash(context) ?: AdPlacement())
    }
    // 广告位来自启动配置；换配置要重建网关，但**初始化 SDK 是副作用**，只能在效果里做。
    // 网关是容器上的普通引用（AdRuntime 持有的是实例本身），所以效果跑完要把新实例放回
    // state，否则这一层永远拿着旧网关。
    var adGateway by remember(ads) { mutableStateOf(container.adGateway) }
    LaunchedEffect(ads) {
        container.updateAdPlacements(ads)
        adGateway = container.adGateway
    }
    // "下次冷启动要不要为 SDK 开屏停一下"只按真实配置记：兜底 ads 里的平台配置是空的，
    // 拿它写会把 SDK 开屏标记误清成 false。
    LaunchedEffect(startup) {
        if (startup != null) AdSplashGate.record(context, startup.ads)
    }

    val fullscreen = rememberFullscreenController()
    val shortPlayPresentation = rememberShortPlayPresentation(backStackEntry)
    val shellState = AppShellState(
        currentRoute = currentRoute,
        tabs = tabs,
        locked = locked,
        fullscreen = fullscreen.isFullscreen,
        showTabIcons = startup?.bottomTabIcons ?: true,
    )
    CompositionLocalProvider(
        LocalFullscreenController provides fullscreen,
        LocalShortPlayPresentation provides shortPlayPresentation,
        LocalMemberEnabled provides memberEnabled,
        LocalForceLogin provides forceLogin,
        // 详情页三块开关：后台「设置 → 详情页」配，缺字段按全显示处理
        LocalDetailSections provides detailSectionsOf(startup),
        // 协议页地址：登录/注册/找回密码三页共用，后台改条款不用发版
        LocalPolicyLinks provides PolicyLinks(
            agreementUrl = startup?.agreementUrl.orEmpty(),
            privacyUrl = startup?.privacyUrl.orEmpty(),
        ),
    ) {
        // 开屏与激励是全屏层，和导航无关：放在 NavHost 外层一次接好，
        // 五个广告位的配置、落地页打开方式、广告网关与激励解锁仓库也由它统一提供。
        AdHost(
            ads = ads,
            context = context,
            runtime = AdRuntime(gateway = adGateway, unlock = container.adUnlockRepository),
        ) {
                AppScaffold(
                    navController = navController,
                    state = shellState,
                ) { padding ->
                    val direction = LocalLayoutDirection.current
                    // 短剧视频延伸到 dock 后方；系统栏占位只保留给前景控件。
                    val contentPadding = shellState.contentPadding(padding, direction)
                    // 登录弹层宿主包住整个 NavHost：任何页面都能唤起登录，不各自持有弹层状态
                    LoginPromptHost(
                        container = container,
                        config = LoginPromptConfig(loginViewModel, loginChoices),
                        onOpenAuthForm = openAuthForm,
                    ) {
                        CompositionLocalProvider(
                            LocalHostBottomInset provides contentPadding.calculateBottomPadding(),
                        ) {
                            Box(
                                modifier = Modifier.padding(contentPadding).consumeWindowInsets(contentPadding),
                            ) {
                                AppNavGraph(
                                    navController = navController,
                                    container = container,
                                    tabOrder = tabOrder,
                                    openAuthForm = openAuthForm,
                                )
                            }
                        }
                    }
                }
                // 锁屏退场用淡出：登录成功那一刻直接消失太硬，淡出 200ms 收得干净。
                // 进场不做动画——冷启动时它本来就该当场就在。
                AnimatedVisibility(
                    visible = locked,
                    enter = EnterTransition.None,
                    exit = fadeOut(animationSpec = tween(durationMillis = LOCK_SCREEN_EXIT_MS)),
                ) {
                    LoginLockScreen(
                        viewModel = loginViewModel,
                        subtitle = FORCE_LOGIN_SUBTITLE,
                        choices = loginChoices,
                        deviceBusy = deviceBusy,
                        actions = LoginHostActions(
                            onSignedIn = { container.onSignedIn() },
                            onRegisterClick = { openAuthForm(AuthForm.REGISTER) },
                            onForgotPasswordClick = { openAuthForm(AuthForm.RESET) },
                            onDeviceLogin = onDeviceLogin,
                        ),
                    )
                }
                // 注册 / 找回密码画在锁屏之上：NavHost 全程不动，所以不存在"切页时露出首页"
                AuthFormOverlay(form = authForm, container = container, onClose = closeAuthForm)
                // 广告隐私同意：等开屏退场、启动配置到位后再弹
                AdConsentGateWhenReady(
                    container = container,
                    context = context,
                    text = ads.consentText,
                    startup = startup,
                )
        }
    }
}


/**
 * 冷启动预热剧场：第一页写进快照，首个短剧的详情也一起拉好。
 *
 * 用户点短剧 tab 时列表与详情都已在本地，页面首帧直接铺内容、不必先转圈；
 * 失败静默，页面自己会重试。
 */
private suspend fun prefetchShortPlay(container: AppContainer, startup: StartupConfig?) {
    if (startup == null) return
    val typeId = startup.shortplayTypes.firstOrNull()?.id
    val feed = container.shortPlayRepository.feed(typeId = typeId) as? AppResult.Success ?: return
    feed.value.firstOrNull()?.let { play -> container.vodRepository.detail(play.id) }
}
@Composable
private fun rememberLoginChoices(startup: StartupConfig?, context: Context): LoginChoices =
    remember(startup) {
        startup?.let { LoginChoices.from(it) } ?: SplashSkinStore.readLoginChoices(context)
    }

/** 锁屏退场的淡出时长：登录成功时有收尾感，又不让人等。 */
private const val LOCK_SCREEN_EXIT_MS = 200


/**
 * 切 tab 仍丢弃普通页面子栈，只有短剧根保留，返回时复用其 VM、媒体源与播放位置。
 *
 * 保存前先弹掉短剧之上的临时页面，避免恢复详情页等已经失效的播放流。
 */
internal fun NavHostController.navigateSingleTop(route: String) {
    if (route == SHORT_PLAY_ROUTE && popBackStack(SHORT_PLAY_ROUTE, inclusive = false)) return
    navigate(route) {
        launchSingleTop = true
        restoreState = true
        popUpTo(graph.startDestinationId) { saveState = true }
    }
}


/** 播放页一次性事件。 */
internal fun Context.showPlayerEvent(event: PlayerEvent) {
    val message = when (event) {
        is PlayerEvent.DanmuFailed -> event.message
        is PlayerEvent.AutoPlayNext -> "正在播放 ${event.episodeTitle}"
        is PlayerEvent.TrySeeBlocked -> "本集需要解锁"
        // 不把 errorCodeName（ERROR_CODE_IO_BAD_HTTP_STATUS 之类）甩给用户
        is PlayerEvent.PlaybackFailed -> "播放中断，请重试"
        PlayerEvent.RequestUpgrade -> "开通会员"
        PlayerEvent.RequestPurchase -> "积分兑换"
    }
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

