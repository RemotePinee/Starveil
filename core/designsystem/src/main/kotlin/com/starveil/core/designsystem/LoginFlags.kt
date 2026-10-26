package com.starveil.core.designsystem

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 强制登录是否开启（服务端 `force_login`）。
 *
 * 开启时客户端冷启动直接锁全屏登录，未登录进不了 App；关闭时登录只是可关闭的弹层。
 * 默认 false：老后端不下发该字段时保持游客可用的现有行为。
 */
val LocalForceLogin = staticCompositionLocalOf { false }

/** 弹出登录层的原因。只影响文案，不影响任何鉴权判断（鉴权永远在服务端）。 */
enum class LoginReason {

    /** 主动点「登录 / 注册」。 */
    GENERAL,

    /** 点收藏 / 追剧这类需要账号的功能。 */
    FAVORITE,

    /** 发评论、发弹幕。 */
    COMMENT,

    /** 起播前被强制登录拦住。 */
    PLAY,
}

/**
 * 登录弹层的出口。
 *
 * feature 页面通过 [LocalLoginPrompt] 唤起登录，不持有 NavController、也不依赖 `:app`
 * （AGENTS.md 第 1 节：feature 之间禁止互相依赖；登录层的宿主在 `:app`）。
 */
fun interface LoginPrompt {

    fun show(reason: LoginReason)
}

/** 默认实现什么都不做：预览与单元测试不必逐个提供宿主。 */
val LocalLoginPrompt = staticCompositionLocalOf { LoginPrompt { } }
