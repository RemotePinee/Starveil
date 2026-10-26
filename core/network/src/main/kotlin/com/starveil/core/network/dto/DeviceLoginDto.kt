package com.starveil.core.network.dto

import kotlinx.serialization.Serializable

/**
 * 设备账号登录结果（插件 `AuthController::postDevicelogin`）：`{"token": "…64 位十六进制…"}`。
 *
 * 必须落盘：首次调用时服务端原样回传客户端生成的 token；
 * 重装/换机恢复时服务端会换发新 token，旧凭据随即失效。
 */
@Serializable
data class DeviceLoginDto(
    val token: String = "",
)
