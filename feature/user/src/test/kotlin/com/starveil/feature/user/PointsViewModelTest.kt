package com.starveil.feature.user

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.data.PointsRepository
import com.starveil.core.model.PointsStatus
import com.starveil.core.model.PointsTask
import com.starveil.core.model.ShareLink
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 积分中心状态持有者。主线：进来拉余额与任务、签到后按服务端结果刷新、领取失败只提示不改余额。
 *
 * 余额不做本地加减（后端 `mac_user.user_points` 是唯一账本），断言都落在"刷新后的新值"上。
 * 用 `advanceUntilIdle` 而不是挂 Turbine 收流：这里只关心最终状态，不关心中间帧。
 */
class PointsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `initial load exposes balance and filters the sign task`() = runTest {
        val viewModel = PointsViewModel(FakePointsRepository())
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertEquals(120, state.points)
        assertEquals(5, state.signPoints)
        assertFalse(state.signedToday)
        // 签到由顶部按钮承担，列表里不能再出现一条 sign
        assertEquals(listOf("watch"), state.tasks.map { it.key })
    }

    @Test
    fun `sign refreshes balance and marks signed`() = runTest {
        val viewModel = PointsViewModel(FakePointsRepository())
        advanceUntilIdle()

        viewModel.sign()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.signedToday)
        assertEquals(125, state.points)
        assertEquals("今日已签到", state.signLabel)
    }

    @Test
    fun `claim failure keeps balance and reports the backend message`() = runTest {
        val repository = FakePointsRepository(claimFailure = AppError.Business(400, "今天已经领取过了"))
        val viewModel = PointsViewModel(repository)
        advanceUntilIdle()

        viewModel.claim("watch")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(120, state.points)
        assertEquals(null, state.submitting)
        assertEquals("今天已经领取过了", viewModel.messages.first())
    }
}

private class FakePointsRepository(
    private var signed: Boolean = false,
    private val claimFailure: AppError? = null,
) : PointsRepository {

    private var points = 120

    override suspend fun status(): AppResult<PointsStatus> =
        AppResult.Success(PointsStatus(points = points, signedToday = signed, signPoints = 5))

    override suspend fun signIn(): AppResult<Int> {
        if (signed) return AppResult.Success(0)
        signed = true
        points += 5
        return AppResult.Success(5)
    }

    override suspend fun tasks(): AppResult<List<PointsTask>> = AppResult.Success(
        listOf(
            PointsTask(key = "sign", title = "每日签到", points = 5, done = signed),
            PointsTask(key = "watch", title = "观看满 30 分钟", points = 10, done = true),
        ),
    )

    override suspend fun claim(key: String): AppResult<Int> =
        claimFailure?.let { AppResult.Failure(it) } ?: AppResult.Success(10)

    override suspend fun shareScore(): AppResult<Int> =
        claimFailure?.let { AppResult.Failure(it) } ?: AppResult.Success(5)

    override suspend fun shareLink(): AppResult<ShareLink> =
        AppResult.Success(ShareLink(url = "https://example.com/s", title = "Starveil", description = ""))
}
