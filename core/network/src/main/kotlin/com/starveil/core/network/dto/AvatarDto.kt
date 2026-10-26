package com.starveil.core.network.dto

import kotlinx.serialization.Serializable

/**
 * 头像上传结果（插件 `UploadController::postUser`）：`{"file": "/upload/avatar/12_1700000000.jpg"}`。
 *
 * 后端回的是站内相对路径，客户端要拼上接口域名才能请求；域名不写死，由调用方传入。
 */
@Serializable
data class AvatarDto(
    val file: String = "",
)
