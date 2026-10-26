package com.starveil.core.data

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.network.dto.ApiEnvelopeDto
import com.starveil.core.network.dto.UserInfoDto
import com.starveil.core.network.dto.UserGroupDto
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户资料仓库。契约见旧 。
 */
class DefaultUserRepositoryTest {

    @Test
    fun `profile fields are mapped from the detail response`() {
        runTest {
            val api = FakeUserApi(
                response = ApiEnvelopeDto(
                    code = 200,
                    msg = "success",
                    data = UserInfoDto(
                        userId = 72,
                        userName = "demo",
                        nickName = "示例昵称",
                        groupId = 2,
                        points = 0,
                        userPoints = 10,
                        gold = "29.00",
                        endTime = 0,
                        group = UserGroupDto(groupId = 2, groupName = "默认会员"),
                    ),
                ),
            )

            val profile = (DefaultUserRepository(api).loadProfile() as AppResult.Success).value

            assertEquals(72, profile.userId)
            assertEquals("示例昵称", profile.nickName)
            assertEquals("默认会员", profile.groupName)
            // 两个余额字段不能互相覆盖
            assertEquals(0, profile.points)
            assertEquals(10, profile.userPoints)
            assertEquals("29.00", profile.gold)
        }
    }

    @Test
    fun `business rejection is surfaced with backend code and message`() {
        runTest {
            val api = FakeUserApi(
                response = ApiEnvelopeDto(code = 403, msg = "您未登陆", data = null),
            )

            val error = (DefaultUserRepository(api).loadProfile() as AppResult.Failure).error

            assertTrue(error is AppError.Business)
            assertEquals(403, (error as AppError.Business).code)
            assertEquals("您未登陆", error.message)
        }
    }

    @Test
    fun `code 200 without data is treated as business failure`() {
        runTest {
            val api = FakeUserApi(response = ApiEnvelopeDto(code = 200, msg = "success", data = null))

            val error = (DefaultUserRepository(api).loadProfile() as AppResult.Failure).error

            assertTrue(error is AppError.Business)
        }
    }

    @Test
    fun `io failure is wrapped as network error`() {
        runTest {
            val api = FakeUserApi(failure = IOException("timeout"))

            val error = (DefaultUserRepository(api).loadProfile() as AppResult.Failure).error

            assertTrue(error is AppError.Network)
        }
    }
}

private class FakeUserApi(
    private val response: ApiEnvelopeDto<UserInfoDto>? = null,
    private val failure: Exception? = null,
) : StubStarveilApi() {

    override suspend fun userInfo(): ApiEnvelopeDto<UserInfoDto> {
        failure?.let { throw it }
        return requireNotNull(response)
    }
}
