package com.starveil.core.data

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.network.ProgressListApi
import com.starveil.core.network.dto.ApiEnvelopeDto
import com.starveil.core.network.dto.VideoProgressDto
import com.starveil.core.network.dto.VideoProgressListDto
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 继续观看仓库（`GET v1.user/videoProgressList`）。
 *
 * 后端顺序就是展示顺序（updated_at desc），仓库不再排序、不再过滤下架影片——
 * 那两件事由服务端 join `mac_vod` 时的 `vod_status = 1` 完成。
 */
class DefaultContinueWatchingRepositoryTest {

    @Test
    fun `list keeps backend order and maps every field`() {
        runTest {
            val api = FakeProgressApi(
                response = ApiEnvelopeDto(
                    code = 200,
                    msg = "success",
                    data = VideoProgressListDto(
                        list = listOf(
                            progress(vodId = 101, name = "示例影片 A", pic = "https://img/a.jpg", indices = 2 to 600),
                            progress(vodId = 102, name = "示例影片 B", pic = null, indices = 0 to 0),
                        ),
                    ),
                ),
            )

            val items = (DefaultContinueWatchingRepository(api).recent() as AppResult.Success).value

            assertEquals(listOf(101, 102), items.map { it.vodId })
            assertEquals("示例影片 A", items.first().vodName)
            assertEquals("https://img/a.jpg", items.first().vodPic)
            assertEquals(3, items.first().episodeNumber)
            assertEquals(600, items.first().progressSec)
            assertNull(items[1].vodPic)
        }
    }

    @Test
    fun `empty list is success not failure`() {
        runTest {
            val api = FakeProgressApi(
                response = ApiEnvelopeDto(
                    code = 200,
                    msg = "success",
                    data = VideoProgressListDto(list = emptyList()),
                ),
            )

            val result = DefaultContinueWatchingRepository(api).recent()

            assertTrue((result as AppResult.Success).value.isEmpty())
        }
    }

    @Test
    fun `login required code is surfaced as business error`() {
        runTest {
            val api = FakeProgressApi(
                response = ApiEnvelopeDto(code = 401, msg = "请先登录", data = null),
            )

            val error = (DefaultContinueWatchingRepository(api).recent() as AppResult.Failure).error

            assertTrue(error is AppError.Business)
            assertEquals(401, (error as AppError.Business).code)
            assertEquals("请先登录", error.message)
        }
    }

    @Test
    fun `null data with success code is business failure`() {
        runTest {
            val api = FakeProgressApi(response = ApiEnvelopeDto(code = 200, msg = "success", data = null))

            val result = DefaultContinueWatchingRepository(api).recent()

            assertTrue((result as AppResult.Failure).error is AppError.Business)
        }
    }

    @Test
    fun `io failure is wrapped as network error`() {
        runTest {
            val api = FakeProgressApi(failure = IOException("timeout"))

            val result = DefaultContinueWatchingRepository(api).recent()

            assertTrue((result as AppResult.Failure).error is AppError.Network)
            assertEquals(1, api.calls)
        }
    }

    private fun progress(
        vodId: Int,
        name: String,
        pic: String?,
        indices: Pair<Int, Int>,
    ) = VideoProgressDto(
        vodId = vodId,
        vodName = name,
        vodPic = pic,
        sourceIndex = 0,
        episodeIndex = indices.first,
        progressSec = indices.second,
        updatedAt = 1760000000,
    )
}

private class FakeProgressApi(
    private val response: ApiEnvelopeDto<VideoProgressListDto>? = null,
    private val failure: Exception? = null,
) : ProgressListApi {

    var calls: Int = 0
        private set

    override suspend fun recent(): ApiEnvelopeDto<VideoProgressListDto> {
        calls++
        failure?.let { throw it }
        return requireNotNull(response) { "缺少响应" }
    }

    override suspend fun progressOf(vodId: Int): ApiEnvelopeDto<VideoProgressDto> =
        ApiEnvelopeDto(code = 200, msg = "ok", data = VideoProgressDto())

    override suspend fun saveProgress(
        vodId: Int,
        sourceIndex: Int,
        episodeIndex: Int,
        positionSec: Int,
    ): ApiEnvelopeDto<String> = ApiEnvelopeDto(code = 200, msg = "ok", data = null)
}
