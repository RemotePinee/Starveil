package com.starveil.core.network

import com.starveil.core.network.dto.ApiEnvelopeDto
import com.starveil.core.network.dto.DeviceLoginDto
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

/**
 * 设备账号（RDA）登录。
 *
 * 单独成接口而不是塞进 [StarveilApi]：它是"没有账号密码也能有个身份"的第二套登录方式，
 * 与用户名密码那条链路并存但互不依赖；拆开之后 `StarveilApi` 也不会因为再加设备相关接口
 * 而撑破 400 行（AGENTS.md 第 2 节）。[StarveilApi] 继承本接口，所以既有调用点不用改。
 */
interface DeviceAuthApi {

    /**
     * 表单 `device_token` / `device_name` / `recovery`（插件 `AuthController::postDevicelogin`）。
     *
     * 首次调用会建号，之后同一 token 即登录；恢复场景下服务端会换发新 token，
     * 所以响应里的 `token` 必须落盘，旧值随即失效。
     */
    @FormUrlEncoded
    @POST(ApiPaths.DEVICE_LOGIN)
    suspend fun deviceLogin(
        @Field("device_token") token: String,
        @Field("device_name") deviceName: String,
        @Field("recovery") recovery: String,
    ): ApiEnvelopeDto<DeviceLoginDto> = throw UnsupportedOperationException(
        // 不能返回一个"看起来合法"的 code=0：Retrofit 对接口默认方法不会发请求，
        // 那样设备登录会静默失败，界面表现为"点了没反应"。
        // 抛异常会被 DefaultAuthRepository.apiCall 接住并变成明确的失败文案。
        "deviceLogin 需要由 Retrofit 生成实现，当前调用走了接口默认实现",
    )
}
