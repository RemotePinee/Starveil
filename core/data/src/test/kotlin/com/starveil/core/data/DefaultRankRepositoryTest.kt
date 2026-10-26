package com.starveil.core.data

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.network.dto.ApiEnvelopeDto
import com.starveil.core.network.dto.RankBoardDto
import com.starveil.core.network.dto.RankCatalogDto
import com.starveil.core.network.dto.RankGroupDto
import com.starveil.core.network.dto.VodDto
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 榜单仓库。两个接口两条契约：
 * `v1.vod/vodphb`（要 order_by/type/page/limit）
 * 与 `v1.vod/vodPhbAll`（无参数、按分类分行）。
 */
class DefaultRankRepositoryTest {

    @Test
    fun `board forwards order type page and default limit`() = runTest {
        val api = FakeRankApi(boardResponse = boardOf(VodDto(id = 201, name = "榜单影片 A")))

        val vods = (DefaultRankRepository(api)
            .board(orderBy = "vod_hits desc", type = 1, page = 1) as AppResult.Success).value

        // 旧榜单页每页固定 20，默认值必须落在请求上
        assertEquals(listOf("vod_hits desc", 1, 1, 20), api.boardArgs)
        assertEquals(201, vods.first().id)
    }

    @Test
    fun `board maps every entry of the flat list`() = runTest {
        val api = FakeRankApi(
            boardResponse = boardOf(
                VodDto(id = 201, name = "榜单影片 A", score = "9.1"),
                VodDto(id = 202, name = "榜单影片 B", remarks = "更新至 12 集"),
            ),
        )

        val vods = (DefaultRankRepository(api).board("vod_hits desc", 1, 1) as AppResult.Success).value

        assertEquals(2, vods.size)
        assertEquals("9.1", vods.first().score)
        assertEquals("更新至 12 集", vods[1].remarks)
    }

    @Test
    fun `catalog keeps promoted and groups apart`() = runTest {
        val api = FakeRankApi(
            catalogResponse = ApiEnvelopeDto(
                code = 200,
                msg = "success",
                data = RankCatalogDto(
                    promoted = listOf(RankGroupDto(typeId = 1, typeName = "电影")),
                    groups = listOf(
                        RankGroupDto(typeId = 2, typeName = "电视剧", vods = listOf(VodDto(id = 302))),
                        RankGroupDto(typeId = 3, typeName = "综艺"),
                    ),
                ),
            ),
        )

        val catalog = (DefaultRankRepository(api).catalog() as AppResult.Success).value

        assertEquals("电影", catalog.promoted.first().typeName)
        assertEquals(2, catalog.groups.size)
        assertEquals("电视剧", catalog.groups.first().typeName)
        assertEquals(302, catalog.groups.first().vods.first().id)
        assertTrue(catalog.groups[1].vods.isEmpty())
    }

    @Test
    fun `business rejection is surfaced as business error`() = runTest {
        val api = FakeRankApi(boardResponse = ApiEnvelopeDto(code = 403, msg = "您未登陆", data = null))

        val error = (DefaultRankRepository(api).board("vod_hits desc", 1, 1) as AppResult.Failure).error

        assertTrue(error is AppError.Business)
        assertEquals(403, (error as AppError.Business).code)
    }

    @Test
    fun `io failure is wrapped as network error`() = runTest {
        val api = FakeRankApi(failure = IOException("timeout"))

        val result = DefaultRankRepository(api).catalog()

        assertTrue((result as AppResult.Failure).error is AppError.Network)
    }

    private fun boardOf(vararg vods: VodDto): ApiEnvelopeDto<RankBoardDto> = ApiEnvelopeDto(
        code = 200,
        msg = "success",
        data = RankBoardDto(list = vods.toList()),
    )
}

private class FakeRankApi(
    private val boardResponse: ApiEnvelopeDto<RankBoardDto>? = null,
    private val catalogResponse: ApiEnvelopeDto<RankCatalogDto>? = null,
    private val failure: Exception? = null,
) : StubStarveilApi() {

    var boardArgs: List<Any>? = null

    override suspend fun vodRank(
        orderBy: String,
        type: Int,
        page: Int,
        limit: Int,
    ): ApiEnvelopeDto<RankBoardDto> {
        failure?.let { throw it }
        boardArgs = listOf(orderBy, type, page, limit)
        return requireNotNull(boardResponse)
    }

    override suspend fun vodRankAll(): ApiEnvelopeDto<RankCatalogDto> {
        failure?.let { throw it }
        return requireNotNull(catalogResponse)
    }
}
