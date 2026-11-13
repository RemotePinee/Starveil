package com.starveil.feature.shortplay

import com.starveil.core.common.AppResult
import com.starveil.core.model.ShortPlay
import com.starveil.core.model.ShortPlayType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 切分类那一拍的状态：先给内容，不给黑底。
 *
 * 背景：占位层只有一层底色，切过去到新分类数据回来这一段屏幕上什么都不像，
 * 用户看到的就是一下黑屏。现在 [ShortPlayViewModel.selectType] 先把新分类的本地快照
 * 铺成 Content——模糊封面、剧名、分类药丸第一帧就在屏上，视频自己慢慢加载。
 * 代价是快照过期时（这一分类如今空了）会先闪一屏 feed 再跳空态，这里一并钉住。
 */
class ShortPlayCategorySwitchTest {
    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    @Test
    fun `category switch paints the new category snapshot before the network`() = runTest {
        val pending = CompletableDeferred<AppResult<List<ShortPlay>>>()
        val repository = ShortPlayTestRepository(
            snapshots = mapOf(22 to listOf(shortPlay(7))),
            response = { pending.await() },
        )
        val viewModel = stableShortPlayViewModel(repository, switchTypes)
        viewModel.selectType(22)
        // 切过去第一帧就是新分类的内容：不等网络，也不经过占位层的纯底色。
        val seeded = viewModel.state.value
        assertTrue(seeded is ShortPlayUiState.Content)
        assertEquals(22, seeded.selectedTypeId)
        assertEquals(listOf(7), (seeded as ShortPlayUiState.Content).plays.map { it.id })
        // 网络回来发现这一分类其实空了，才落到空态。
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
