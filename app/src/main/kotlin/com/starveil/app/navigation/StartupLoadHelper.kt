package com.starveil.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.starveil.app.AppContainer
import com.starveil.core.common.AppResult
import com.starveil.core.common.DetailSections
import com.starveil.core.model.LoginMethod
import com.starveil.core.model.StartupConfig
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 等启动配置的上限。
 *
 * 冷启动第一次请求要建 TLS 连接，2 秒经常不够——超时后按"没有配置"处理，
 * 强制登录就会漏判（用户看到的是直接进首页）。8 秒是占位页可接受的等待上限，
 * 有缓存/已建连的情况下通常 1 秒内就回来了。
 */
private const val STARTUP_TIMEOUT_MS = 8_000L

/**
 * 启动配置加载过程。抽离以满足单个文件 400 行硬约束。
 */
@Composable
internal fun rememberStartupLoad(container: AppContainer): StartupLoad {
    // 上次已经拿到过配置就同步进 Ready：冷启动不经过占位页，首帧就是最终布局。
    // 进程内缓存由开屏页填好，这里通常直接命中，连一次网络往返都省了。
    val cached = remember(container) {
        container.startupRepository.cached()?.let { StartupLoad.Ready(it) }
    }
    val state by produceState<StartupLoad>(initialValue = cached ?: StartupLoad.Loading, container) {
        container.restoreSessionCookies()
        val config = cached?.config ?: fetchStartup(container)
        value = StartupLoad.Ready(config)
        afterStartupLoaded(container, config)
    }
    return state
}

/** 拉一次配置；超时按"没有配置"处理，不让启动被网络按住。 */
private suspend fun fetchStartup(container: AppContainer): StartupConfig? {
    val result = withTimeoutOrNull(STARTUP_TIMEOUT_MS) { container.startupRepository.load() }
    return (result as? AppResult.Success)?.value
}

/**
 * 配置到手之后的收尾。
 *
 * - 强制登录开启时不做静默设备登录：不然冷启动瞬间就把用户"登录"上了，锁屏永远不出现。
 *   锁屏上有「设备一键登录」按钮，用户点一下照样零输入进 App，只是这一步由他确认。
 * - 资料预取失败不影响启动，下次进"我的"会再拉一次。
 */
private suspend fun afterStartupLoaded(container: AppContainer, config: StartupConfig?) {
    if (shouldSilentSignIn(config)) container.silentDeviceSignIn()
    container.userRepository.loadProfile()
}

/**
 * 这次冷启动该不该顺手把游客变成设备账号。
 *
 * 三个条件同时成立才算：拿到了配置、没开强制登录、后台允许设备登录。
 * 起个名字是因为原来那行 `if` 读不出"这是在判断要不要静默登录"。
 */
private fun shouldSilentSignIn(config: StartupConfig?): Boolean =
    config != null && !config.forceLogin && LoginMethod.DEVICE in config.loginMethods

/** 详情页三块开关：老后端不下发时按全显示。 */
internal fun detailSectionsOf(startup: StartupConfig?): DetailSections =
    startup?.detailSections ?: DetailSections.ALL
