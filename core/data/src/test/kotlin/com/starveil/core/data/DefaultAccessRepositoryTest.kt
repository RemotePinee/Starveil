package com.starveil.core.data

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.model.TrySeeAccess
import com.starveil.core.network.dto.ApiEnvelopeDto
import com.starveil.core.network.dto.TrySeeDto
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 试看仓库。契约见旧 ，调用点 。
 *
 * 三件必须守住的旧行为：
 * 1. 请求只有 `id` / `nid` / `mid` 三个 query，`mid` 恒为模块 id `"1"`；
 * 2. 播放源下标不上传；
 * 3. `access` 走 `TrySeeRules.access`，剩免费次数优先于 VIP 状态。
 */
class DefaultAccessRepositoryTest {

    @Test
    fun `check try see forwards vod and episode with fixed module id`() = runTest {
        val api = FakeTrySeeApi(gateResponse = gateOf(status = 0, freeCount = 0))

        DefaultAccessRepository(api).checkTrySee(vodId = 101, episodeNumber = 3, sourceIndex = 0)

        assertEquals(listOf("101", "3", "1"), api.args)
    }

    @Test
    fun `remaining free count wins over vip status`() = runTest {
        val api = FakeTrySeeApi(gateResponse = gateOf(status = 1, freeCount = 5, trySeeMinutes = 6))

        val gate = (DefaultAccessRepository(api)
            .checkTrySee(vodId = 101, episodeNumber = 3, sourceIndex = 0) as AppResult.Success).value

        assertEquals(TrySeeAccess.FREE, gate.access)
        assertEquals(6, gate.trySeeMinutes)
    }

    @Test
    fun `paid episode without free count needs pay`() = runTest {
        val api = FakeTrySeeApi(gateResponse = gateOf(status = 2, freeCount = 0, trySeeMinutes = 888))

        val gate = (DefaultAccessRepository(api)
            .checkTrySee(vodId = 101, episodeNumber = 9, sourceIndex = 1) as AppResult.Success).value

        assertEquals(TrySeeAccess.NEED_PAY, gate.access)
        assertEquals(888, gate.trySeeMinutes)
    }

    @Test
    fun `business rejection is surfaced as business error`() = runTest {
        val api = FakeTrySeeApi(
            gateResponse = ApiEnvelopeDto(code = 403, msg = "您未登陆", data = null),
        )

        val error = (DefaultAccessRepository(api)
            .checkTrySee(vodId = 101, episodeNumber = 3, sourceIndex = 0) as AppResult.Failure).error

        assertTrue(error is AppError.Business)
        assertEquals(403, (error as AppError.Business).code)
    }

    @Test
    fun `io failure is wrapped as network error`() = runTest {
        val api = FakeTrySeeApi(failure = IOException("timeout"))

        val result = DefaultAccessRepository(api).checkTrySee(vodId = 101, episodeNumber = 3, sourceIndex = 0)

        assertTrue((result as AppResult.Failure).error is AppError.Network)
    }

    private fun gateOf(
        status: Int,
        freeCount: Int,
        trySeeMinutes: Int = 6,
    ): ApiEnvelopeDto<TrySeeDto> = ApiEnvelopeDto(
        code = 200,
        msg = "success",
        data = TrySeeDto(trySeeMinutes = trySeeMinutes, status = status, freeCount = freeCount),
    )
}

private class FakeTrySeeApi(
    private val gateResponse: ApiEnvelopeDto<TrySeeDto>? = null,
    private val failure: Exception? = null,
) : StubStarveilApi() {

    /** 收到的三个 query，顺序固定 id / nid / mid。 */
    var args: List<String>? = null

    override suspend fun checkVodTrySee(
        vodId: String,
        episodeNumber: String,
        moduleId: String,
    ): ApiEnvelopeDto<TrySeeDto> {
        failure?.let { throw it }
        args = listOf(vodId, episodeNumber, moduleId)
        return requireNotNull(gateResponse)
    }
}
