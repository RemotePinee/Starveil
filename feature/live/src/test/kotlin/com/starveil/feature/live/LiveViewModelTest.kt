package com.starveil.feature.live

import app.cash.turbine.test
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.data.LivePage
import com.starveil.core.data.LiveRepository
import com.starveil.core.model.LiveChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Rule
import org.junit.Test

/**
 * 列表三态 + 分页测试。
 *
 * 历史版本接口失败只打日志，页面永远停在空列表；
 * 守的是"失败必须变成看得见的错误态"。分页部分守的是
 * "后台有几百个频道时不能只显示第一页"——早期客户端不给 `page`/`limit`，
 * 后端默认只回 20 条，列表看起来永远只有 20 个。
 */
class LiveViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    @Test
    fun `list success exposes channels`() = runTest {
        val viewModel = LiveViewModel(FakeLiveRepository(page(channel())))

        viewModel.state.test {
            val state = awaitItem() as LiveUiState.Content
            Assert.assertEquals(1, state.items.size)
            Assert.assertEquals("频道1", state.items.first().name)
            Assert.assertFalse(state.isRefreshing)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `empty list becomes empty state`() = runTest {
        val viewModel = LiveViewModel(FakeLiveRepository(page()))

        Assert.assertTrue(viewModel.state.value is LiveUiState.Empty)
    }

    @Test
    fun `list failure becomes readable error`() = runTest {
        val viewModel = LiveViewModel(
            FakeLiveRepository(AppResult.Failure(AppError.Network(null))),
        )

        val state = viewModel.state.value as LiveUiState.Error
        Assert.assertTrue(state.message.contains("网络"))
    }

    @Test
    fun `business failure keeps backend message`() = runTest {
        val viewModel = LiveViewModel(
            FakeLiveRepository(AppResult.Failure(AppError.Business(500, "直播已下线"))),
        )

        val state = viewModel.state.value as LiveUiState.Error
        Assert.assertEquals("直播已下线", state.message)
    }

    @Test
    fun `refresh reloads and keeps content`() = runTest {
        val repository = FakeLiveRepository(page(channel()))
        val viewModel = LiveViewModel(repository)

        viewModel.refresh()

        val state = viewModel.state.value as LiveUiState.Content
        Assert.assertEquals(1, state.items.size)
        Assert.assertFalse(state.isRefreshing)
        Assert.assertEquals(2, repository.calls)
    }

    @Test
    fun `load more appends the next page`() = runTest {
        val repository = FakeLiveRepository(
            page(channel(1), hasMore = true),
            page(channel(2), hasMore = false),
        )
        val viewModel = LiveViewModel(repository)

        viewModel.loadMore()

        val state = viewModel.state.value as LiveUiState.Content
        Assert.assertEquals(listOf(1L, 2L), state.items.map { it.id })
        Assert.assertFalse(state.hasMore)
        Assert.assertFalse(state.isLoadingMore)
        Assert.assertEquals(2, repository.calls)
    }

    @Test
    fun `load more is ignored when there is no next page`() = runTest {
        val repository = FakeLiveRepository(page(channel()))
        val viewModel = LiveViewModel(repository)

        viewModel.loadMore()

        Assert.assertEquals(1, repository.calls)
    }

    @Test
    fun `duplicate channels across pages are collapsed`() = runTest {
        val repository = FakeLiveRepository(
            page(channel(1), hasMore = true),
            page(channel(1), channel(2), hasMore = false),
        )
        val viewModel = LiveViewModel(repository)

        viewModel.loadMore()

        val state = viewModel.state.value as LiveUiState.Content
        Assert.assertEquals(listOf(1L, 2L), state.items.map { it.id })
    }

    private fun page(
        vararg items: LiveChannel,
        hasMore: Boolean = false,
        total: Int = items.size,
    ): AppResult<LivePage> = AppResult.Success(
        LivePage(
            items = items.toList(),
            total = total,
            groups = emptyList(),
            hasMore = hasMore,
        ),
    )
}

/**
 * 按顺序回放预设页；请求次数超出后重复最后一页，
 * 这样"刷新时重复请求第一页"之类的场景不用单独写桩。
 */
private class FakeLiveRepository(vararg results: AppResult<LivePage>) : LiveRepository {

    private val pages = results.toList()

    var calls: Int = 0
        private set

    override suspend fun list(page: Int, limit: Int, group: String): AppResult<LivePage> {
        calls += 1
        return pages.getOrElse(calls - 1) { pages.last() }
    }
}

internal fun channel(id: Long = 1, url: String? = "https://cdn.example.com/live/$id.m3u8") = LiveChannel(
    id = id,
    name = "频道$id",
    cover = null,
    url = url,
)
