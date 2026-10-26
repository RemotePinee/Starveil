package com.starveil.feature.user

import com.starveil.core.common.AppResult
import com.starveil.core.data.UserRepository
import com.starveil.core.model.UserProfile

/** 资料仓库替身：只关心"被调了几次、返回什么"。 */
internal class FakeUserRepository(
    var result: AppResult<UserProfile> = AppResult.Success(demoProfile()),
) : UserRepository {

    var loadCalls: Int = 0
        private set

    override suspend fun loadProfile(): AppResult<UserProfile> {
        loadCalls += 1
        return result
    }
}

/** 资料样例，字段取值参考 `core/network/src/test/resources/fixtures/v1_user_detail.json`。 */
internal fun demoProfile(): UserProfile = UserProfile(
    userId = 72,
    userName = "demo",
    nickName = "示例昵称",
    portrait = "",
    groupId = 2,
    groupName = "默认会员",
    level = "3",
    points = 0,
    userPoints = 10,
    gold = "29.00",
    status = 1,
    memberEndTime = 0,
    viewCount = "128",
    viewSeconds = 7_380,
    isVip = false,
    isAgents = "0",
)
