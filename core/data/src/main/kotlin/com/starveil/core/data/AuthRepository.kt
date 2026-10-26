package com.starveil.core.data

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.model.Session
import com.starveil.core.network.StarveilApi

/**
 * 登录与会话。
 *
 * 契约来自旧 ：
 * `POST /v1.auth/login`，表单字段 `user_name` / `user_pwd`，返回 `BaseResult<String>`。
 * 登录态不靠返回值，靠响应下发的 cookie（见 [Session]）。
 */
interface AuthRepository {

    suspend fun login(userName: String, password: String): AppResult<Session>

    /**
     * 注册：`POST v1.auth/register`，
     * 表单 `user_name` / `user_pwd` / `user_pwd2` / `uid`，`uid` 传渠道号
     * （旧 传 `App.CHANNE`）。
     *
     * 只返回是否成功，不落盘会话：注册成功后仍要再登录一次
     * 注册响应里的 cookie 不足以当会话用。
     */
    suspend fun register(
        userName: String,
        password: String,
        passwordConfirm: String,
        uid: String,
    ): AppResult<Unit>

    /**
     * 带短信验证码的注册（手机号注册）。
     *
     * 单独一个方法而不是给 [register] 加参数：老后端/老 fake 只认 4 个字段，
     * 默认实现直接转调 [register] 并忽略验证码；正式实现会把 `code` 一起提交。
     */
    suspend fun registerWithCode(payload: RegisterPayload): AppResult<Unit> =
        register(
            userName = payload.userName,
            password = payload.password,
            passwordConfirm = payload.passwordConfirm,
            uid = payload.inviteCode,
        )

    /** 注册短信验证码，`ac` 固定 `phone`。 */
    suspend fun sendRegisterCode(phone: String): AppResult<Unit>

    /**
     * 找回密码：GET + query，`user_pwd` 与 `user_pwd2`
     * 必须相同，`type` 固定 `2`。
     */
    suspend fun resetPassword(
        phone: String,
        password: String,
        passwordConfirm: String,
        code: String,
    ): AppResult<Unit>

    /** 找回密码短信验证码，与注册验证码不是同一路径。 */
    suspend fun sendResetCode(phone: String): AppResult<Unit>

    /** 冷启动恢复；没有会话返回 null。 */
    suspend fun restore(): Session?

    /**
     * 设备账号登录（RDA）：首次建号，之后同一 token 即登录，重装后凭设备指纹找回。
     *
     * 默认实现只给不支持的后端/测试 fake 用：正式实现见 [DefaultAuthRepository]。
     * 给默认值是为了新增这个方法时不必改掉每个 feature 的测试 fake。
     */
    suspend fun deviceLogin(
        token: String,
        deviceName: String,
        recovery: String,
    ): AppResult<DeviceSignIn> =
        AppResult.Failure(AppError.Business(code = 0, message = "当前仓库不支持设备登录"))

    suspend fun logout()
}

class DefaultAuthRepository(
    private val api: StarveilApi,
    private val cookies: () -> Map<String, String>,
    private val store: SessionStore,
) : AuthRepository {

    /**
     * 登录。
     *
     * 注意只在"响应 URL 精确等于登录路径"时才落盘 cookie
     * 导致注册后必须再登录一次；
     * 新实现由 cookie jar 统一保存，登录后立刻可用。
     */
    override suspend fun login(userName: String, password: String): AppResult<Session> =
        apiCall { api.login(userName = userName, password = password) }
            .flatMap { envelope ->
                if (envelope.code != SUCCESS_CODE) return@flatMap businessFailure(envelope.code, envelope.msg)

                val session = Session.fromCookies(cookies())
                if (!session.isLoggedIn) {
                    return@flatMap businessFailure(
                        envelope.code,
                        "登录成功但未下发 user_id cookie：${envelope.msg.orEmpty()}",
                    )
                }
                store.save(session)
                AppResult.Success(session)
            }

    override suspend fun register(
        userName: String,
        password: String,
        passwordConfirm: String,
        uid: String,
    ): AppResult<Unit> = registerWithCode(
        RegisterPayload(
            userName = userName,
            password = password,
            passwordConfirm = passwordConfirm,
            inviteCode = uid,
        ),
    )

    override suspend fun registerWithCode(payload: RegisterPayload): AppResult<Unit> = apiCall {
        api.register(
            userName = payload.userName,
            password = payload.password,
            passwordConfirm = payload.passwordConfirm,
            uid = payload.inviteCode,
            code = payload.smsCode,
        )
    }.requireSuccess()

    override suspend fun sendRegisterCode(phone: String): AppResult<Unit> =
        apiCall { api.sendRegisterCode(phone = phone) }.requireSuccess()

    override suspend fun sendResetCode(phone: String): AppResult<Unit> =
        apiCall { api.sendResetCode(phone = phone) }.requireSuccess()

    override suspend fun resetPassword(
        phone: String,
        password: String,
        passwordConfirm: String,
        code: String,
    ): AppResult<Unit> = apiCall {
        api.resetPassword(
            phone = phone,
            password = password,
            passwordConfirm = passwordConfirm,
            code = code,
        )
    }.requireSuccess()

    override suspend fun restore(): Session? = store.load()?.takeIf { it.isLoggedIn }

    /** 只清本地 SP，不请求 `/v1.auth/logout`；保持同样的取舍，避免服务端把会话真的作废。 */
    override suspend fun logout() = store.clear()

    override suspend fun deviceLogin(
        token: String,
        deviceName: String,
        recovery: String,
    ): AppResult<DeviceSignIn> = apiCall {
        api.deviceLogin(token = token, deviceName = deviceName, recovery = recovery)
    }.flatMap { envelope ->
        if (envelope.code != SUCCESS_CODE) return@flatMap businessFailure(envelope.code, envelope.msg)

        val session = Session.fromCookies(cookies())
        if (!session.isLoggedIn) {
            return@flatMap businessFailure(envelope.code, "设备登录未下发会话 cookie")
        }
        store.save(session)
        // 服务端可能在恢复时换发 token；空值表示沿用本地那份
        val issued = envelope.data?.token?.takeIf { it.isNotBlank() } ?: token
        AppResult.Success(DeviceSignIn(session = session, token = issued))
    }
}

/** 设备登录结果：会话 + 应当落盘的凭据（恢复时服务端会换发新 token）。 */
data class DeviceSignIn(val session: Session, val token: String)

/** 注册提交参数。收成数据类是为了别让 `registerWithCode` 顶到 detekt 的参数上限。 */
data class RegisterPayload(
    val userName: String,
    val password: String,
    val passwordConfirm: String,
    val inviteCode: String = "",
    /** 手机号注册的短信验证码；账号注册传空串。 */
    val smsCode: String = "",
)
