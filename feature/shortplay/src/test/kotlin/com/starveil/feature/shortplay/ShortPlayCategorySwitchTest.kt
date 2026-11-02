package com.starveil.feature.shortplay

import com.starveil.core.common.AppResult
import com.starveil.core.model.ShortPlay
import com.starveil.core.model.ShortPlayType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 切分类那一拍的状态：空分类不能被过期快照冒充成"有内容"，也不该闪一屏转圈。
 *
 * 为什么要单独立一组：短剧页的浮层（右栏 / 入口条 / 标题 / 进度）只活在 feed 里，
 * 只要 state 在这一拍是 `Content`，屏幕上就会先画出一整套浮层，再被真正的空态撤掉。
 * 所以"当前分类有没有短剧"必须由 state 一处决定——这里锁住的就是那条状态序列。
 */
class ShortPlayCategorySwitchTest {
    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    @Test
    fun `category switch never paints the new category snapshot as content`() = runTest {
        val pending = CompletableDeferred<AppResult<List<ShortPlay>>>()
        val repository = ShortPlayTestRepository(
            snapshots = mapOf(22 to listOf(shortPlay(7))),
            response = { pending.await() },
        )
        val viewModel = stableShortPlayViewModel(repository, switchTypes)
        viewModel.selectType(22)
        // 快照不再冒充内容：这个分类其实已经空了，屏幕上不会先组合一屏 feed。
        assertFalse(viewModel.state.value is ShortPlayUiState.Content)
        pending.complete(AppResult.Success(emptyList()))
        assertTrue(viewModel.state.value is ShortPlayUiState.Empty)
    }

    @Test
    fun `switching category keeps the old state instead of flashing loading`() = runTest {
        val pending = CompletableDeferred<AppResult<List<ShortPlay>>>()
        var requests = 0
        val repository = ShortPlayTestRepository(response = {
            if (++requests == 1) AppResult.Success(emptyList()) else pending.await()
        })
        val viewModel = stableShortPlayViewModel(repository, switchTypes)
        assertTrue(viewModel.state.value is ShortPlayUiState.Empty)
        viewModel.selectType(22)
        // 切走那一拍原地等：selectedTypeId 仍指回旧分类（页面据此不上屏，只剩占位层），
        // 不落到 Loading——落点是占位层，不是转圈。
        val switching = viewModel.state.value
        assertTrue(switching is ShortPlayUiState.Empty)
        assertEquals(21, switching.selectedTypeId)
        pending.complete(AppResult.Success(emptyList()))
        assertEquals(22, viewModel.state.value.selectedTypeId)
        assertTrue(viewModel.state.value is ShortPlayUiState.Empty)
    }
}

/** 与其它短剧测试同一组 id：21 是进页默认分类，22 是被切过去的那个。 */
private val switchTypes = listOf(ShortPlayType(21, "短剧"), ShortPlayType(22, "微短剧"))
