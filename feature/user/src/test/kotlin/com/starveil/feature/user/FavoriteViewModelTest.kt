package com.starveil.feature.user

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 收藏页。六条：首屏成功、空列表、首屏失败（并可重试）、取消成功就地移除、取消失败保留、
 * 首帧快照先铺且在刷新失败时不被清空。
 *
 * 页码断言和"取消后就地移除"是重点：在删除成功后会重置页码整页重拉
 * 这条测试防它被顺手搬回来。
 */
class FavoriteViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `first page loads favorites`() = runTest {
        val favorites = FakeFavoriteRepository(
            pages = mutableMapOf(1 to AppResult.Success(listOf(favoriteItem(101), favoriteItem(102)))),
        )
        val viewModel = FavoriteViewModel(favorites, FakeAuthRepository(restored = demoSession()))

        val state = viewModel.state.value
        assertEquals(listOf(101, 102), state.items.map { it.vodId })
        assertEquals(1, state.loadedPage)
        assertFalse(state.isInitialLoading)
        assertFalse(state.isEmpty)
        assertEquals(listOf(1), favorites.requestedPages)
    }

    @Test
    fun `empty first page shows the empty state`() = runTest {
        val favorites = FakeFavoriteRepository()
        val viewModel = FavoriteViewModel(favorites, FakeAuthRepository(restored = demoSession()))

        val state = viewModel.state.value
        assertTrue(state.isEmpty)
        assertFalse(state.hasMore)
        assertNull(state.errorMessage)
    }

    @Test
    fun `first page failure shows the error state and retry reloads`() = runTest {
        val favorites = FakeFavoriteRepository(
            pages = mutableMapOf(1 to AppResult.Failure(AppError.Network(cause = null))),
        )
        val viewModel = FavoriteViewModel(favorites, FakeAuthRepository(restored = demoSession()))

        val failed = viewModel.state.value
        assertTrue(failed.isError)
        assertNotNull(failed.errorMessage)

        favorites.pages[1] = AppResult.Success(listOf(favoriteItem(301)))
        viewModel.retry()

        assertEquals(listOf(1, 1), favorites.requestedPages)
        assertEquals(listOf(301), viewModel.state.value.items.map { it.vodId })
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun `cancelling a favorite removes it in place`() = runTest {
        val favorites = FakeFavoriteRepository(
            pages = mutableMapOf(1 to AppResult.Success(listOf(favoriteItem(101), favoriteItem(102)))),
        )
        val viewModel = FavoriteViewModel(favorites, FakeAuthRepository(restored = demoSession()))

        viewModel.remove(viewModel.state.value.items.first())

        assertEquals(1, favorites.removeCalls)
        assertEquals(listOf(102), viewModel.state.value.items.map { it.vodId })
        assertEquals("已取消收藏", viewModel.state.value.message)
        assertTrue(viewModel.state.value.removingIds.isEmpty())
    }

    @Test
    fun `failed cancel keeps the item and reports a message`() = runTest {
        val favorites = FakeFavoriteRepository(
            pages = mutableMapOf(1 to AppResult.Success(listOf(favoriteItem(101), favoriteItem(102)))),
            removeResult = AppResult.Failure(AppError.Business(code = 500, message = "取消失败")),
        )
        val viewModel = FavoriteViewModel(favorites, FakeAuthRepository(restored = demoSession()))

        viewModel.remove(viewModel.state.value.items.first())

        val state = viewModel.state.value
        assertEquals(listOf(101, 102), state.items.map { it.vodId })
        assertEquals("取消失败", state.message)
        assertTrue(state.removingIds.isEmpty())
    }

    @Test
    fun `cached snapshot fills the page and a failed refresh keeps it`() = runTest {
        val favorites = FakeFavoriteRepository(
            pages = mutableMapOf(1 to AppResult.Failure(AppError.Network(cause = null))),
            cached = listOf(favoriteItem(901), favoriteItem(902)),
        )
        val viewModel = FavoriteViewModel(favorites, FakeAuthRepository(restored = demoSession()))

        val state = viewModel.state.value
        assertEquals(listOf(901, 902), state.items.map { it.vodId })
        assertFalse(state.isInitialLoading)
        assertNull(state.errorMessage)
        assertEquals("网络异常，请检查网络后重试", state.message)
    }
}
