package com.starveil.feature.rank

import app.cash.turbine.test
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.data.RankRepository
import com.starveil.core.model.RankCatalog
import com.starveil.core.model.RankGroup
import com.starveil.core.model.Vod
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Test

/**
 * 榜单页状态测试。
 *
 * 覆盖两个接口的差异：目录失败与空目录都必须有独立状态；列表翻页失败不能清空已有数据；
 * 切分类必须从第一页重来。fake 手写，不引 mock 框架；状态流用 turbine 收。
 */
class RankViewModelTest {

    @get:org.junit.Rule
    val dispatcherRule = MainDispatcherRule()

    @Test
    fun `catalog success exposes categories and selects the first one`() = runTest {
        val repository = FakeRankRepository(
            catalogResult = AppResult.Success(catalog(1 to "电影", 2 to "剧集")),
            boards = mapOf((1 to 1) to AppResult.Success(emptyList())),
        )
        val viewModel = RankViewModel(repository)

        viewModel.state.test {
            val state = awaitItem()
            val catalog = state.catalog as RankCatalogState.Content

            Assert.assertEquals(listOf(1, 2), catalog.categories.map { it.typeId })
            Assert.assertEquals(listOf("电影", "剧集"), catalog.categories.map { it.name })
            Assert.assertEquals(1, state.selectedTypeId ?: 0)
            Assert.assertEquals(listOf(1), repository.boardCalls.map { it.type })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `catalog failure becomes catalog error without requesting board`() = runTest {
        val repository = FakeRankRepository(catalogResult = AppResult.Failure(AppError.Network(null)))
        val viewModel = RankViewModel(repository)

        viewModel.state.test {
            val state = awaitItem()
            val catalog = state.catalog as RankCatalogState.Error

            Assert.assertTrue(catalog.message.contains("网络"))
            Assert.assertTrue(state.board is RankBoardState.Idle)
            Assert.assertTrue(repository.boardCalls.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `board first page success fills content with page one`() {
        val repository = FakeRankRepository(
            catalogResult = AppResult.Success(catalog(1 to "电影")),
            boards = mapOf((1 to 1) to AppResult.Success(listOf(vod(1), vod(2)))),
        )

        // pageSize = 3 时 2 条小于一页，直接判定到底。
        val board = RankViewModel(repository, pageSize = 3).state.value.board as RankBoardState.Content

        Assert.assertEquals(listOf(1, 2), board.items.map { it.id })
        Assert.assertEquals(1, board.page)
        Assert.assertTrue(board.endReached)
        Assert.assertNull(board.appendError)
    }

    @Test
    fun `loading more appends the next page and dedupes by id`() {
        val repository = FakeRankRepository(
            catalogResult = AppResult.Success(catalog(1 to "电影")),
            boards = mapOf(
                (1 to 1) to AppResult.Success(listOf(vod(1), vod(2))),
                (1 to 2) to AppResult.Success(listOf(vod(2), vod(3))),
            ),
        )
        val viewModel = RankViewModel(repository, pageSize = 2)

        viewModel.loadMore()

        val board = viewModel.state.value.board as RankBoardState.Content
        Assert.assertEquals(listOf(1, 2, 3), board.items.map { it.id })
        Assert.assertEquals(2, board.page)
        Assert.assertEquals(listOf(1, 2), repository.boardCalls.map { it.page })
    }

    @Test
    fun `loading more failure keeps existing items and exposes retry`() {
        val repository = FakeRankRepository(
            catalogResult = AppResult.Success(catalog(1 to "电影")),
            boards = mapOf((1 to 1) to AppResult.Success(listOf(vod(1), vod(2)))),
        )
        val viewModel = RankViewModel(repository, pageSize = 2)

        // 第 2 页没配置结果，fake 返回 Failure。
        viewModel.loadMore()

        val board = viewModel.state.value.board as RankBoardState.Content
        Assert.assertEquals(listOf(1, 2), board.items.map { it.id })
        Assert.assertEquals(1, board.page)
        Assert.assertFalse(board.isAppending)
        Assert.assertNotNull(board.appendError)
    }

    @Test
    fun `switching category resets page and reloads from first page`() {
        val repository = FakeRankRepository(
            catalogResult = AppResult.Success(catalog(1 to "电影", 2 to "剧集")),
            boards = mapOf(
                (1 to 1) to AppResult.Success(listOf(vod(1), vod(2))),
                (1 to 2) to AppResult.Success(listOf(vod(3), vod(4))),
                (2 to 1) to AppResult.Success(listOf(vod(9))),
            ),
        )
        val viewModel = RankViewModel(repository, pageSize = 2)
        viewModel.loadMore()
        Assert.assertEquals(2, (viewModel.state.value.board as RankBoardState.Content).page)

        viewModel.selectCategory(2)

        val board = viewModel.state.value.board as RankBoardState.Content
        Assert.assertEquals(2, viewModel.state.value.selectedTypeId ?: 0)
        Assert.assertEquals(listOf(9), board.items.map { it.id })
        Assert.assertEquals(1, board.page)
        Assert.assertEquals(2, repository.boardCalls.last().type)
        Assert.assertEquals(1, repository.boardCalls.last().page)
    }

    private fun catalog(vararg types: Pair<Int, String>) = RankCatalog(
        promoted = emptyList(),
        groups = types.map { (typeId, name) ->
            RankGroup(typeId = typeId, typeName = name, typeImage = null, vods = emptyList())
        },
    )

    private fun vod(id: Int) = Vod(
        id = id,
        name = "影片$id",
        poster = null,
        remarks = "更新至第1集",
        year = "2026",
        area = "内地",
        score = "9.1",
        blurb = null,
        playSources = emptyList(),
    )
}

/** 记录每次 board 调用的参数，便于断言页码/分类是否真的重置。 */
private data class BoardCall(
    val orderBy: String,
    val type: Int,
    val page: Int,
    val limit: Int,
)

private class FakeRankRepository(
    private val catalogResult: AppResult<RankCatalog>,
    private val boards: Map<Pair<Int, Int>, AppResult<List<Vod>>> = emptyMap(),
) : RankRepository {

    val boardCalls = mutableListOf<BoardCall>()

    override suspend fun board(
        orderBy: String,
        type: Int,
        page: Int,
        limit: Int,
    ): AppResult<List<Vod>> {
        boardCalls += BoardCall(orderBy = orderBy, type = type, page = page, limit = limit)
        // 没配置的页返回失败，用来模拟翻页请求出错。
        return boards[type to page] ?: AppResult.Failure(AppError.Unknown(null))
    }

    override suspend fun catalog(): AppResult<RankCatalog> = catalogResult
}
