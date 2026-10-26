package com.starveil.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 沉浸/全屏状态。
 *
 * 播放器自己只管方向和画面，底栏归 :app；两边通过这个控制器通信，
 * 避免把 NavController 或 Scaffold 状态塞进 feature 模块（AGENTS 第 1 节）。
 */
class FullscreenController {

    var isFullscreen: Boolean by mutableStateOf(false)
        private set

    fun set(value: Boolean) {
        isFullscreen = value
    }
}

val LocalFullscreenController = staticCompositionLocalOf { FullscreenController() }

/** 播放器里用：进入/退出全屏时同步 app 级状态。 */
@Composable
fun rememberFullscreenController(): FullscreenController =
    androidx.compose.runtime.remember { FullscreenController() }
