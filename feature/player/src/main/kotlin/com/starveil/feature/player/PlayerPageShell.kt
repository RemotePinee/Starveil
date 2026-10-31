package com.starveil.feature.player

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 播放页的纯 UI 状态：当前弹层、横滑预览位置、控制层显隐、亮度读写、弹幕偏好。
 *
 * 全是"换一下就要存一次盘没有意义"的东西，不进 ViewModel；收成一个持有者是为了让
 * [PlayerScreen] 本体守得住 80 行与 detekt 的圈复杂度上限（AGENTS.md 第 2 节）。
 */
@Stable
internal class PlayerPageState(context: Context, val danmuPrefs: DanmuPrefs) {

    /** 当前打开的底部弹层；null 表示没开。 */
    var sheet by mutableStateOf<PlayerSheetKind?>(null)

    /** 横滑预览位置：不为空时进度条画这个值，松手后清空回到真实播放位置。 */
    var seekPreview by mutableStateOf<Long?>(null)

    /** 控制层显隐（纯 UI 状态，换一次存盘没有意义，所以不进 ViewModel）。 */
    val chrome = ChromeVisibility()

    /** 亮度/音量读写器：进入前的原始值由它抓，页面销毁时按它还原。 */
    val levels = GestureLevels(context)
}

@Composable
internal fun rememberPlayerPage(): PlayerPageState {
    val context = LocalContext.current
    // 弹幕显示偏好（开关/字号/颜色/行数/区域/不透明度）收在 DanmuPrefs：
    // 行数与区域有约束关系，也让主 Composable 的圈复杂度不超 detekt 上限
    val danmuPrefs = rememberDanmuPrefs()
    val page = remember(context, danmuPrefs) { PlayerPageState(context, danmuPrefs) }
    // 窗口亮度记在页面这一层：GestureLevels 构造时抓进入前的原始值，
    // 页面销毁时按它还原——否则退出播放页后，详情页/首页会保留调过的亮度。
    DisposableEffect(page) { onDispose { page.levels.restoreBrightness() } }
    return page
}

/** 弹层打开时控制层保持可见；播放中 3 秒无操作就把控制层收起来。 */
@Composable
internal fun PlayerChromeIdle(state: PlayerUiState, page: PlayerPageState) {
    val shown = chromeShownOf(page.chrome.visible, page.sheet)
    ChromeIdleEffect(playing = state.isPlaying, shown = shown, idleKey = page.chrome.idleKey) {
        page.chrome.visible = false
    }
}

/**
 * 控制层的两个环境输入：播放位置与是否沉浸。
 *
 * 收成 data class 是为了守住 detekt 的 `LongParameterList`（data class 的构造参数不计入）。
 * 由 [PlayerScreen] 每次重组新建，但只作为参数向下传：`position` 是同一个 State 实例、
 * `immersive` 是布尔，按值比较相等；[ChromeConfig] 的 remember 键取的仍是
 * `extras.immersive` 这个值，不会因为持有者换实例而失效。
 */
internal data class ChromeExtras(
    /** 播放位置：只在底栏的时间与进度条叶子里读。 */
    val position: State<Long>,
    /** 全屏且横屏才算沉浸（见 [rememberImmersive]）。 */
    val immersive: Boolean,
)

/**
 * 控制层（顶栏 + 底栏）。
 *
 * [ChromeConfig] 必须 remember：它的 5 个函数字段按引用比较，每次重组都新建等于每次都换
 * 配置，底栏与顶栏那棵 121 行的子树就会跟着位置流一起 2Hz 重组（A15）。
 */
@Composable
internal fun PlayerChrome(
    state: PlayerUiState,
    actions: PlayerActions,
    page: PlayerPageState,
    extras: ChromeExtras,
) {
    if (state.isBlocked) return
    val danmuPrefs = page.danmuPrefs
    val shown = chromeShownOf(page.chrome.visible, page.sheet)
    val config = remember(shown, extras.immersive, danmuPrefs.enabled, actions, page.chrome, state.speed) {
        ChromeConfig(
            visible = shown,
            danmuEnabled = danmuPrefs.enabled,
            immersive = extras.immersive,
            onInteract = { page.chrome.show() },
            onToggleDanmu = { danmuPrefs.enabled = !danmuPrefs.enabled },
            onOpenSheet = { page.sheet = it },
            // 倍速不再开弹层：点一下切下一档，按钮上直接显示当前档位
            onCycleSpeed = { actions.onSpeedSelected(PlayerSpeeds.next(state.speed)) },
        )
    }
    CompositionLocalProvider(LocalSeekPreview provides page.seekPreview) {
        PlayerChromeOverlay(
            state = state,
            actions = actions,
            config = config,
            position = extras.position,
        )
    }
}

/** 底部弹层组：选集 / 倍速 / 弹幕。弹层是 Activity 级窗口，内嵌 16:9 小窗也不会被压扁。 */
@Composable
internal fun PlayerSheets(
    state: PlayerUiState,
    actions: PlayerActions,
    page: PlayerPageState,
    immersive: Boolean,
) {
    PlayerSheetHost(
        kind = page.sheet,
        state = state,
        actions = actions,
        onDismiss = { page.sheet = null },
        immersive = immersive,
        danmu = DanmuSettings(style = page.danmuPrefs.style(), events = page.danmuPrefs.events()),
    )
}
