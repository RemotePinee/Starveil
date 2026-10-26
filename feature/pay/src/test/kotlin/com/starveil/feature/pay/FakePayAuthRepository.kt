package com.starveil.feature.pay

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.data.AuthRepository
import com.starveil.core.model.Session

/**
 * 支付页只用到 [restore]：Hero 的会员状态要先认登录态，再等资料给到期日。
 * 其余登录动作支付页不调，统一返回失败——真被调到就说明接错线了。
 */
internal class FakePayAuthRepository(
    var restored: Session? = demoPaySession(),
) : AuthRepository {

    override suspend fun login(userName: String, password: String): AppResult<Session> =
        AppResult.Failure(NOT_USED)

    override suspend fun register(
        userName: String,
        password: String,
        passwordConfirm: String,
        uid: String,
    ): AppResult<Unit> = AppResult.Failure(NOT_USED)

    override suspend fun sendRegisterCode(phone: String): AppResult<Unit> =
        AppResult.Failure(NOT_USED)

    override suspend fun resetPassword(
        phone: String,
        password: String,
        passwordConfirm: String,
        code: String,
    ): AppResult<Unit> = AppResult.Failure(NOT_USED)

    override suspend fun sendResetCode(phone: String): AppResult<Unit> =
        AppResult.Failure(NOT_USED)

    override suspend fun restore(): Session? = restored

    override suspend fun logout() = Unit
}

/** 已登录会话：支付页只关心"有没有会话"，其余字段填能过 `isLoggedIn` 的值即可。 */
internal fun demoPaySession(): Session = Session(
    userId = "42",
    userName = "demo",
    nickName = "演示用户",
    groupId = "1",
    groupName = "黄金会员",
    userCheck = "abc123",
    portrait = "",
)

private val NOT_USED = AppError.Business(500, "支付页不调用登录接口")
