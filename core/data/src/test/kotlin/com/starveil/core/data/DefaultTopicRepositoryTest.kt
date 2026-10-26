package com.starveil.core.data

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.network.dto.ApiEnvelopeDto
import com.starveil.core.network.dto.PageDto
import com.starveil.core.network.dto.TopicDetailDto
import com.starveil.core.network.dto.TopicDto
import com.starveil.core.network.dto.VodDto
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 专题仓库。契约见旧 与 。
 *
 * 守两件事：列表从分页壳里取 `data.list`，详情是单对象并要把 `vod_list` 映射成 `Vod`。
 */
class DefaultTopicRepositoryTest {

    @Test
    fun `list keeps string topic ids and maps every entry`() = runTest {
        val api = FakeTopicApi(
            listResponse = ApiEnvelopeDto(
                code = 200,
                msg = "success",
                data = PageDto(
                    total = 2,
                    page = 1,
                    limit = 10,
                    list = listOf(
                        TopicDto(id = "3", name = "港片精选", poster = "https://img.example.com/3.jpg"),
                        TopicDto(id = "4", name = "科幻"),
                    ),
                ),
            ),
        )

        val topics = (DefaultTopicRepository(api).list() as AppResult.Success).value

        assertEquals(2, topics.size)
        assertEquals("3", topics.first().id)
        assertEquals("港片精选", topics.first().name)
        assertEquals("4", topics[1].id)
    }

    @Test
    fun `detail forwards topic id and maps embedded vods`() = runTest {
        val api = FakeTopicApi(
            detailResponse = ApiEnvelopeDto(
                code = 200,
                msg = "success",
                data = TopicDetailDto(
                    name = "港片精选",
                    tag = "港片",
                    vods = listOf(VodDto(id = 101, name = "示例影片 A")),
                ),
            ),
        )

        val detail = (DefaultTopicRepository(api).detail(topicId = "3") as AppResult.Success).value

        assertEquals("3", api.detailArgs)
        assertEquals("港片", detail.tag)
        assertEquals(101, detail.vods.first().id)
    }

    @Test
    fun `business rejection is surfaced as business error`() = runTest {
        val api = FakeTopicApi(
            listResponse = ApiEnvelopeDto(code = 403, msg = "您未登陆", data = null),
        )

        val error = (DefaultTopicRepository(api).list() as AppResult.Failure).error

        assertTrue(error is AppError.Business)
        val business = error as AppError.Business
        assertEquals(403, business.code)
        assertEquals("您未登陆", business.message)
    }

    @Test
    fun `io failure is wrapped as network error`() = runTest {
        val api = FakeTopicApi(failure = IOException("timeout"))

        val result = DefaultTopicRepository(api).detail(topicId = "3")

        assertTrue((result as AppResult.Failure).error is AppError.Network)
    }
}

private class FakeTopicApi(
    private val listResponse: ApiEnvelopeDto<PageDto<TopicDto>>? = null,
    private val detailResponse: ApiEnvelopeDto<TopicDetailDto>? = null,
    private val failure: Exception? = null,
) : StubStarveilApi() {

    var detailArgs: String? = null

    override suspend fun topicList(): ApiEnvelopeDto<PageDto<TopicDto>> {
        failure?.let { throw it }
        return requireNotNull(listResponse)
    }

    override suspend fun topicDetail(topicId: String): ApiEnvelopeDto<TopicDetailDto> {
        failure?.let { throw it }
        detailArgs = topicId
        return requireNotNull(detailResponse)
    }
}
