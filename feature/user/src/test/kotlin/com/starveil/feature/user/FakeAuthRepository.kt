package com.starveil.feature.user

import com.starveil.core.common.AppResult
import com.starveil.core.data.AuthRepository
import com.starveil.core.model.Session

/**
 * 仓库替身。只关心"被调用了几次、返回什么"，不碰网络。
 *
 * 注册与找回密码的返回值可注入（默认成功），调用计数用来断言
 * "校验失败时根本没发请求"。两个验证码接口共用一个结果：调用方不会同时用两次。
 */
internal class FakeAuthRepository(
    private val restored: Session? = null,
    private val loginResult: AppResult<Session> = AppResult.Success(demoSession()),
    private val registerResult: AppResult<Unit> = AppResult.Success(Unit),
    private val sendCodeResult: AppResult<Unit> = AppResult.Success(Unit),
    private val resetResult: AppResult<Unit> = AppResult.Success(Unit),
) : AuthRepository {

    var loginCalls: Int = 0
        private set

    var logoutCalls: Int = 0
        private set

    var registerCalls: Int = 0
        private set

    var sendRegisterCodeCalls: Int = 0
        private set

    var sendResetCodeCalls: Int = 0
        private set

    var resetPasswordCalls: Int = 0
        private set

    var lastUserName: String? = null
        private set

    var lastUid: String? = null
        private set

    var lastRegisterPasswordConfirm: String? = null
        private set

    var lastPhone: String? = null
        private set

    var lastResetCode: String? = null
        private set

    override suspend fun login(userName: String, password: String): AppResult<Session> {
        loginCalls += 1
        lastUserName = userName
        return loginResult
    }

    override suspend fun restore(): Session? = restored

    override suspend fun logout() {
        logoutCalls += 1
    }

    override suspend fun register(
        userName: String,
        password: String,
        passwordConfirm: String,
        uid: String,
    ): AppResult<Unit> {
        registerCalls += 1
        lastUserName = userName
        lastUid = uid
        lastRegisterPasswordConfirm = passwordConfirm
        return registerResult
    }

    override suspend fun sendRegisterCode(phone: String): AppResult<Unit> {
        sendRegisterCodeCalls += 1
        lastPhone = phone
        return sendCodeResult
    }

    override suspend fun resetPassword(
        phone: String,
        password: String,
        passwordConfirm: String,
        code: String,
    ): AppResult<Unit> {
        resetPasswordCalls += 1
        lastPhone = phone
        lastResetCode = code
        return resetResult
    }

    override suspend fun sendResetCode(phone: String): AppResult<Unit> {
        sendResetCodeCalls += 1
        lastPhone = phone
        return sendCodeResult
    }
}

/** cookie 表还原出来的会话样例，字段取值参考 `SessionTest`。 */
internal fun demoSession(): Session = Session(
    userId = "42",
    userName = "demo",
    nickName = "演示用户",
    groupId = "1",
    groupName = "黄金会员",
    userCheck = "abc123",
    portrait = "",
)
