package com.starveil.feature.shortplay

import com.starveil.core.common.AppResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 信息流的软上限与两个版本号。
 *
 * 分开一个文件而不是塞回 `ShortPlayViewModelTest`：那边已经贴着 detekt 的类行数上限。
 */
class ShortPlayWindowTest {
    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    @Test
    fun `append caps the list and keeps the current play inside the window`() = runTest {
        // 每页 100 条、页号连续：刷到第 6 页就是 600 条，必然越过软上限
        val repository = ShortPlayTestRepository(response = { call ->
            val base = (call.page - 1) * 100
            AppResult.Success((1..100).map { shortPlay(base + it) })
        })
        val viewModel = ShortPlayViewModel(repository, pageSize = 100, shuffle = { it })
        repeat(4) { viewModel.loadMore() }
        assertEquals(500, viewModel.playIds().size)
        viewModel.onPageSettled(450)
        viewModel.loadMore()
        val ids = viewModel.playIds()
        assertEquals(PLAY_WINDOW, ids.size)
        // 当前播放项与它后面一整段都还在，被丢掉的只有头部
        assertEquals(450, viewModel.content().currentPlayId)
        assertTrue(ids.contains(450))
        assertEquals(600, ids.last())
        assertTrue(ids.containsAll((401..600).toList()))
    }

    @Test
    fun `settling a page keeps the same plays instance and pages version`() = runTest {
        val repository = ShortPlayTestRepository(
            pages = mutableMapOf(1 to AppResult.Success(listOf(shortPlay(1), shortPlay(2)))),
        )
        val viewModel = stableShortPlayViewModel(repository)
        val before = viewModel.content()
        viewModel.onPageSettled(2)
        assertEquals(2, viewModel.content().currentPlayId)
        // 落页只改 currentPlayId：列表实例与版本号都不动，才不会重跑 buildFeedPages
        assertSame(before.plays, viewModel.content().plays)
        assertEquals(before.pagesVersion, viewModel.content().pagesVersion)
        repository.pages[2] = AppResult.Success(listOf(shortPlay(3)))
        viewModel.loadMore()
        assertEquals(before.pagesVersion + 1, viewModel.content().pagesVersion)
    }

    @Test
    fun `window keeps an anchor sitting at the head and leaves short lists alone`() {
        val plays = (1..10).map(::shortPlay)
        assertEquals(plays, keepWindowAround(plays, keepId = 1, limit = 20))
        assertEquals(listOf(1, 2, 3, 4), keepWindowAround(plays, keepId = 1, limit = 4).map { it.id })
        // 锚点不在列表里也按头部处理，不抛异常
        assertEquals(listOf(1, 2, 3), keepWindowAround(plays, keepId = 999, limit = 3).map { it.id })
    }
}
