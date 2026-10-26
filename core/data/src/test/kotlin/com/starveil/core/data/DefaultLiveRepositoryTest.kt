package com.starveil.core.data

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.network.dto.ApiEnvelopeDto
import com.starveil.core.network.dto.LiveDto
import com.starveil.core.network.dto.PageDto
import com.starveil.core.network.dto.LivePageDto
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 直播仓库。契约见旧 （`v1.zhibo`，无参数）。
 *
 * 只做列表；`url` 是可空字段，缺失时必须落成 null 而不是空串——
 * 播放器要靠 null 判断"这个频道没给地址"。
 */
class DefaultLiveRepositoryTest {

    @Test
    fun `list maps channel fields and keeps nullable url`() = runTest {
        val api = FakeLiveApi(
            response = ApiEnvelopeDto(
                code = 200,
                msg = "success",
                data = LivePageDto(
                    list = listOf(
                        LiveDto(
                            id = 12,
                            name = "CCTV-1 综合",
                            img = "https://img.example.com/live/1.jpg",
                            url = "https://live.example.com/cctv1.m3u8",
                        ),
                        LiveDto(id = 13, name = "无地址频道"),
                    ),
                ),
            ),
        )

        val channels = (DefaultLiveRepository(api).list() as AppResult.Success).value.items

        assertEquals(2, channels.size)
        assertEquals(12L, channels.first().id)
        assertEquals("CCTV-1 综合", channels.first().name)
        assertEquals("https://img.example.com/live/1.jpg", channels.first().cover)
        assertEquals("https://live.example.com/cctv1.m3u8", channels.first().url)
        assertNull(channels[1].url)
        assertNull(channels[1].cover)
    }

    @Test
    fun `business rejection is surfaced as business error`() = runTest {
        val api = FakeLiveApi(response = ApiEnvelopeDto(code = 400, msg = "直播已下线", data = null))

        val error = (DefaultLiveRepository(api).list() as AppResult.Failure).error

        assertTrue(error is AppError.Business)
        assertEquals("直播已下线", (error as AppError.Business).message)
    }

    @Test
    fun `io failure is wrapped as network error`() = runTest {
        val api = FakeLiveApi(failure = IOException("timeout"))

        val result = DefaultLiveRepository(api).list()

        assertTrue((result as AppResult.Failure).error is AppError.Network)
    }

    @Test
    fun `empty feed drops the cached page so deleted channels stop showing`() = runTest {
        val snapshot = PageSnapshotStore(
            SnapshotStore(java.nio.file.Files.createTempDirectory("live-cache").toFile(), Dispatchers.Unconfined),
        )
        val seeded = DefaultLiveRepository(
            api = FakeLiveApi(
                response = ApiEnvelopeDto(
                    code = 200,
                    data = LivePageDto(list = listOf(LiveDto(id = 1, name = "已删频道"))),
                ),
            ),
            snapshot = snapshot,
        )
        seeded.list()
        assertTrue(seeded.cachedLivePages().orEmpty().isNotEmpty())

        // 后台把频道删光：响应是空列表，旧快照必须作废
        val emptied = DefaultLiveRepository(
            api = FakeLiveApi(response = ApiEnvelopeDto(code = 200, data = LivePageDto(list = emptyList()))),
            snapshot = snapshot,
        )
        emptied.list()

        assertNull(emptied.cachedLivePages())
    }
}

private class FakeLiveApi(
    private val response: ApiEnvelopeDto<LivePageDto>? = null,
    private val failure: Exception? = null,
) : StubStarveilApi() {

    override suspend fun liveList(page: Int, limit: Int, group: String): ApiEnvelopeDto<LivePageDto> {
        failure?.let { throw it }
        return requireNotNull(response)
    }
}
