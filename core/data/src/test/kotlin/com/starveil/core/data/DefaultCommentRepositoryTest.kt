package com.starveil.core.data

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.network.CommentApi
import com.starveil.core.network.dto.ApiEnvelopeDto
import com.starveil.core.network.dto.CommentDto
import com.starveil.core.network.dto.CommentListDto
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评论仓库（`GET/POST v1.comment`，服务端 `CommentController.php`）。
 *
 * 重点守三件事：分页参数原样透传、`total` 不能被当前页条数替代、
 * 未登录的 401 必须落成 [AppError.Business]（UI 据此提示登录而不是网络错误）。
 */
class DefaultCommentRepositoryTest {

    @Test
    fun `list forwards paging and maps comments`() = runTest {
        val api = FakeCommentApi(
            listResponse = ApiEnvelopeDto(
                code = 200,
                msg = "success",
                data = CommentListDto(
                    total = 42,
                    page = 2,
                    limit = 20,
                    list = listOf(
                        CommentDto(
                            id = 900,
                            content = "来二刷",
                            nickName = "游客",
                            createdAt = 1_729_290_000L,
                        ),
                    ),
                ),
            ),
        )

        val page = (DefaultCommentRepository(api).list(vodId = 7, page = 2, limit = 20) as AppResult.Success).value

        assertEquals(Triple(7, 2, 20), api.listArgs)
        assertEquals(42, page.total)
        assertEquals(2, page.page)
        assertEquals(1, page.items.size)
        assertEquals("来二刷", page.items.first().content)
        assertEquals(1_729_290_000L, page.items.first().createdAt)
    }

    @Test
    fun `blank nick name falls back to the guest label`() = runTest {
        val api = FakeCommentApi(
            listResponse = ApiEnvelopeDto(
                code = 200,
                data = CommentListDto(list = listOf(CommentDto(id = 1, content = "无昵称"))),
            ),
        )

        val page = (DefaultCommentRepository(api).list(vodId = 7) as AppResult.Success).value

        assertEquals("游客", page.items.first().nickName)
        assertEquals("", page.items.first().portrait)
    }

    @Test
    fun `post sends the vod id and content as form fields`() = runTest {
        val api = FakeCommentApi(
            postResponse = ApiEnvelopeDto(code = 200, msg = "评论已发布", data = Unit),
        )

        val result = DefaultCommentRepository(api).post(vodId = 7, content = "第三集好看")

        assertTrue(result is AppResult.Success)
        assertEquals(listOf("7", "第三集好看"), api.postArgs)
    }

    @Test
    fun `signed out post is surfaced as business 401`() = runTest {
        val api = FakeCommentApi(
            postResponse = ApiEnvelopeDto(code = 401, msg = "请先登录", data = null),
        )

        val error = (DefaultCommentRepository(api).post(vodId = 7, content = "第三集好看") as AppResult.Failure).error

        assertTrue(error is AppError.Business)
        assertEquals(401, (error as AppError.Business).code)
        assertEquals("请先登录", (error as AppError.Business).message)
    }

    @Test
    fun `io failure is wrapped as network error`() = runTest {
        val api = FakeCommentApi(failure = IOException("timeout"))

        val result = DefaultCommentRepository(api).list(vodId = 7)

        assertTrue((result as AppResult.Failure).error is AppError.Network)
        assertNull(api.listArgs)
    }
}

private class FakeCommentApi(
    private val listResponse: ApiEnvelopeDto<CommentListDto> =
        ApiEnvelopeDto(code = 200, data = CommentListDto()),
    private val postResponse: ApiEnvelopeDto<Unit> = ApiEnvelopeDto(code = 200),
    private val failure: IOException? = null,
) : CommentApi {

    var listArgs: Triple<Int, Int, Int>? = null
    var postArgs: List<String>? = null

    override suspend fun list(vodId: Int, page: Int, limit: Int): ApiEnvelopeDto<CommentListDto> {
        failure?.let { throw it }
        listArgs = Triple(vodId, page, limit)
        return listResponse
    }

    override suspend fun post(vodId: String, content: String): ApiEnvelopeDto<Unit> {
        failure?.let { throw it }
        postArgs = listOf(vodId, content)
        return postResponse
    }
}
