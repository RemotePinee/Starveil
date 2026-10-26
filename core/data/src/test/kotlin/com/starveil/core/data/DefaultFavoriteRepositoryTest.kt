package com.starveil.core.data

import com.starveil.core.model.FavoriteItem
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.network.dto.ApiEnvelopeDto
import com.starveil.core.network.dto.FavoriteDto
import com.starveil.core.network.dto.FavoriteTypeDto
import com.starveil.core.network.dto.FavoriteVodDto
import com.starveil.core.network.dto.PageDto
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏仓库。契约见旧 ：
 * 列表走 `v1.user/favs`，增删共用 `v1.user/ulog` 但方法不同。
 */
class DefaultFavoriteRepositoryTest {

    @Test
    fun `list sends the vod collection type and maps items`() {
        runTest {
            val api = FakeFavoriteApi(
                listResponse = ApiEnvelopeDto(
                    code = 200,
                    msg = "success",
                    data = PageDto(
                        total = 1,
                        page = 2,
                        limit = 10,
                        list = listOf(
                            FavoriteDto(
                                ulogId = 27,
                                ulogRid = 101,
                                ulogTime = 1560877169,
                                data = FavoriteVodDto(
                                    id = 101,
                                    name = "示例影片 A",
                                    pic = "https://img.example.com/a.jpg",
                                    type = FavoriteTypeDto(typeName = "综艺"),
                                ),
                            ),
                        ),
                    ),
                ),
            )

            val items = (DefaultFavoriteRepository(api).list(page = 2) as AppResult.Success).value

            // 旧列表页固定 ulog_type=2、每页 10 条
            assertEquals(Triple(2, 10, 2), api.listArgs)
            assertEquals(1, items.size)
            assertEquals(101, items.first().vodId)
            assertEquals("示例影片 A", items.first().title)
            assertEquals("综艺", items.first().categoryName)
        }
    }

    @Test
    fun `add posts the resource id and returns success`() {
        runTest {
            val api = FakeFavoriteApi()

            val result = DefaultFavoriteRepository(api).add(vodId = 101)

            assertTrue(result is AppResult.Success)
            assertEquals(Triple("101", "1", "2"), api.addArgs)
        }
    }

    @Test
    fun `remove sends all ids in one comma joined call`() {
        runTest {
            val api = FakeFavoriteApi()

            val result = DefaultFavoriteRepository(api).remove(vodIds = listOf(101, 102))

            assertTrue(result is AppResult.Success)
            assertEquals("101,102" to "2", api.removeArgs)
        }
    }
    @Test
    fun `remove updates cached favorites and empty first page is cached`() {
        runTest {
            val cacheDir = java.nio.file.Files.createTempDirectory("favorite-cache").toFile()
            try {
                val pageResponse = ApiEnvelopeDto(
                    code = 200,
                    data = page(total = 2, items = listOf(favorite(101), favorite(102))),
                )
                val repository = DefaultFavoriteRepository(
                    api = FakeFavoriteApi(listResponse = pageResponse),
                    snapshot = PageSnapshotStore(SnapshotStore(cacheDir)),
                )
                repository.list(page = 1)
                repository.remove(listOf(101, 102))
                assertEquals(false, repository.cachedFavoriteStatus(101))
                assertTrue(repository.cachedFavorites().orEmpty().isEmpty())
                val emptyRepository = DefaultFavoriteRepository(
                    api = FakeFavoriteApi(listResponse = ApiEnvelopeDto(code = 200, data = page(0, emptyList()))),
                    snapshot = PageSnapshotStore(SnapshotStore(cacheDir)),
                )
                emptyRepository.list(page = 1)
                assertEquals(emptyList<FavoriteItem>(), emptyRepository.cachedFavorites())
            } finally {
                cacheDir.deleteRecursively()
            }
        }
    }

    @Test
    fun `business rejection on list is surfaced as business error`() {
        runTest {
            val api = FakeFavoriteApi(
                listResponse = ApiEnvelopeDto(code = 403, msg = "您未登陆", data = null),
            )

            val error = (DefaultFavoriteRepository(api).list(page = 1) as AppResult.Failure).error

            assertTrue(error is AppError.Business)
            assertEquals(403, (error as AppError.Business).code)
        }
    }

    @Test
    fun `business rejection on add keeps the backend message`() {
        runTest {
            val api = FakeFavoriteApi(actionCode = 409, actionMessage = "已经收藏过了")

            val error = (DefaultFavoriteRepository(api).add(vodId = 101) as AppResult.Failure).error

            assertTrue(error is AppError.Business)
            assertEquals("已经收藏过了", (error as AppError.Business).message)
        }
    }

    @Test
    fun `io failure is wrapped as network error`() {
        runTest {
            val api = FakeFavoriteApi(failure = IOException("timeout"))

            val result = DefaultFavoriteRepository(api).remove(vodIds = listOf(101))

            assertTrue((result as AppResult.Failure).error is AppError.Network)
            assertNull(api.removeArgs)
        }
    }

    @Test
    fun `contains walks past the first page instead of trusting one screen`() {
        runTest {
            val api = FakeFavoriteApi(
                pages = mapOf(
                    1 to page(total = 130, items = (1..60).map(::favorite)),
                    2 to page(total = 130, items = (61..120).map(::favorite)),
                    3 to page(total = 130, items = (121..130).map(::favorite)),
                ),
            )

            val result = DefaultFavoriteRepository(api).contains(vodId = 125)

            assertEquals(AppResult.Success(true), result)
            assertEquals(listOf(1, 2, 3), api.listPages)
        }
    }

    @Test
    fun `contains reports missing only after the last page`() {
        runTest {
            val api = FakeFavoriteApi(
                pages = mapOf(
                    1 to page(total = 65, items = (1..60).map(::favorite)),
                    2 to page(total = 65, items = (61..65).map(::favorite)),
                ),
            )

            val result = DefaultFavoriteRepository(api).contains(vodId = 999)

            // total=65 → 两页翻完就收尾，不会为一部不在收藏里的片子一直翻下去
            assertEquals(AppResult.Success(false), result)
            assertEquals(listOf(1, 2), api.listPages)
        }
    }

    @Test
    fun `contains follows total across sparse and empty pages`() {
        runTest {
            val api = FakeFavoriteApi(
                pages = mapOf(
                    1 to page(total = 130, items = listOf(favorite(1))),
                    2 to page(total = 130, items = emptyList()),
                    3 to page(total = 130, items = listOf(favorite(125))),
                ),
            )

            assertEquals(AppResult.Success(true), DefaultFavoriteRepository(api).contains(vodId = 125))
            assertEquals(listOf(1, 2, 3), api.listPages)
        }
    }

    @Test
    fun `contains reports unknown at the scan cap even for maximum total`() {
        runTest {
            for (total in listOf(3060, Int.MAX_VALUE)) {
                val api = FakeFavoriteApi(
                    listResponse = ApiEnvelopeDto(code = 200, data = page(total, (1..60).map(::favorite))),
                )

                val result = DefaultFavoriteRepository(api).contains(vodId = 999)

                assertTrue(result is AppResult.Failure)
                val error = (result as AppResult.Failure).error
                assertTrue(error is AppError.Unknown)
                assertEquals("收藏状态未知：已达到 50 页查询上限", (error as AppError.Unknown).cause?.message)
                assertEquals((1..50).toList(), api.listPages)
            }
        }
    }

    @Test
    fun `contains surfaces a business rejection instead of faking not favorited`() {
        runTest {
            val api = FakeFavoriteApi(
                listResponse = ApiEnvelopeDto(code = 403, msg = "您未登陆", data = null),
            )

            val error = (DefaultFavoriteRepository(api).contains(vodId = 101) as AppResult.Failure).error

            assertTrue(error is AppError.Business)
            assertEquals(403, (error as AppError.Business).code)
        }
    }
}

private class FakeFavoriteApi(
    private val listResponse: ApiEnvelopeDto<PageDto<FavoriteDto>>? = null,
    /** 按页给响应：`contains` 必须能翻到第一页之后。 */
    private val pages: Map<Int, PageDto<FavoriteDto>> = emptyMap(),
    private val actionCode: Int = 200,
    private val actionMessage: String = "success",
    private val failure: Exception? = null,
) : StubStarveilApi() {

    var listArgs: Triple<Int, Int, Int>? = null
    var addArgs: Triple<String, String, String>? = null
    val listPages = mutableListOf<Int>()
    var removeArgs: Pair<String, String>? = null

    override suspend fun favoriteList(
        page: Int,
        limit: Int,
        ulogType: Int,
    ): ApiEnvelopeDto<PageDto<FavoriteDto>> {
        failure?.let { throw it }
        listArgs = Triple(page, limit, ulogType)
        listPages += page
        pages[page]?.let { paged -> return ApiEnvelopeDto(code = 200, msg = "success", data = paged) }
        return requireNotNull(listResponse)
    }

    override suspend fun favoriteAdd(
        vodId: String,
        moduleId: String,
        type: String,
    ): ApiEnvelopeDto<String> {
        failure?.let { throw it }
        addArgs = Triple(vodId, moduleId, type)
        return ApiEnvelopeDto(code = actionCode, msg = actionMessage, data = null)
    }

    override suspend fun favoriteRemove(ids: String, type: String): ApiEnvelopeDto<String> {
        failure?.let { throw it }
        removeArgs = ids to type
        return ApiEnvelopeDto(code = actionCode, msg = actionMessage, data = null)
    }
}

/** 收藏条目只关心影片 id：`contains` 按 `vodId` 判定。 */
private fun favorite(vodId: Int): FavoriteDto = FavoriteDto(vodId = vodId, title = "影片 $vodId")

private fun page(total: Int, items: List<FavoriteDto>): PageDto<FavoriteDto> =
    PageDto(total = total, limit = 60, list = items)
