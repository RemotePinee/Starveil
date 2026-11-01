package com.starveil.feature.shortplay

import com.starveil.core.common.AppResult
import com.starveil.core.model.ShortPlayType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * 进页默认分类：顶部 tab 第一个 → 全量分类第一个 → 0（「全部」）。
 *
 * 顶部条只认 state 里的 `selectedTypeId` 判选中，所以"第一个 chip 是选中的"与
 * "首屏拉的是哪个分类"在这里是同一条断言；再钉一次请求参数，避免两处各算一次后漂移。
 */
class ShortPlayInitialTypeTest {
    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    @Test
    fun `first tab drives the initial feed and the chip selected state`() = runTest {
        val repository = ShortPlayTestRepository(
            pages = mutableMapOf(1 to AppResult.Success(listOf(shortPlay(1)))),
        )
        val tabs = listOf(ShortPlayType(38, "逆袭"), ShortPlayType(61, "都市"))
        val viewModel = ShortPlayViewModel(
            repository, types = TYPES, tabs = tabs, shuffle = { it },
        )
        // 选中态与首屏请求同一个值：顶部第一个 chip 进页就是选中的
        assertEquals(38, viewModel.content().selectedTypeId)
        assertEquals(38, repository.calls.single().typeId)
    }

    @Test
    fun `default type id degrades tabs then types then all`() {
        val tabs = listOf(ShortPlayType(38, "逆袭"))
        assertEquals(38, defaultShortPlayTypeId(tabs, TYPES))
        assertEquals(25, defaultShortPlayTypeId(emptyList(), TYPES))
        assertEquals(0, defaultShortPlayTypeId(emptyList(), emptyList()))
        // 顶部配了个脏 id 不该把进页打成「全部」
        assertEquals(25, defaultShortPlayTypeId(listOf(ShortPlayType(0, "")), TYPES))
    }

    private companion object {
        /** 与后台当前配置同形：短剧分类一长串，顶部 tab 只放其中几个。 */
        val TYPES = listOf(ShortPlayType(25, "短剧"), ShortPlayType(26, "古装"), ShortPlayType(27, "都市"))
    }
}
