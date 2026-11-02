package com.starveil.feature.shortplay

import com.starveil.core.common.AppResult
import com.starveil.core.model.ShortPlayType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 分类页的两级联动：一级换组、二级默认「全部」、请求落在正确的 `type_id` 上。
 */
class ShortPlayCategoryViewModelTest {
    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val tree = listOf(
        ShortPlayType(
            21, "红果",
            listOf(ShortPlayType(22, "都市豪门"), ShortPlayType(23, "逆袭爽剧")),
        ),
        ShortPlayType(31, "微短剧", listOf(ShortPlayType(32, "甜宠"))),
    )

    @Test
    fun `entering from a sub category selects its parent and queries the sub`() = runTest {
        val repository = ShortPlayTestRepository(
            pages = mutableMapOf(1 to AppResult.Success(listOf(shortPlay(1)))),
        )
        val viewModel = ShortPlayCategoryViewModel(
            repository, parents = tree, initialTypeId = 23, pageSize = 2,
        )

        val state = viewModel.state.value
        assertEquals(21, state.selectedParentId)
        assertEquals(23, state.selectedChildId)
        assertEquals(listOf(22, 23), state.children.map { it.id })
        assertEquals(listOf(ShortPlayFeedCall(23, 1)), repository.calls)
    }

    @Test
    fun `entering from a parent keeps its sub row on all`() = runTest {
        val repository = ShortPlayTestRepository(pages = mutableMapOf(1 to AppResult.Success(emptyList())))
        val viewModel = ShortPlayCategoryViewModel(
            repository, parents = tree, initialTypeId = 21, pageSize = 2,
        )

        val state = viewModel.state.value
        assertEquals(21, state.selectedParentId)
        assertEquals(ALL_CATEGORY_ID, state.selectedChildId)
        // 「全部」按父分类查：服务端查父分类会带上它的全部子分类
        assertEquals(listOf(ShortPlayFeedCall(21, 1)), repository.calls)
        // 选中某个父分类时二级行就是它的直接子分类，平铺逻辑不参与
        assertEquals(listOf(22, 23), state.visibleChildren.map { it.id })
    }

    @Test
    fun `switching parent replaces the sub row and falls back to all`() = runTest {
        val repository = ShortPlayTestRepository(
            pages = mutableMapOf(1 to AppResult.Success(listOf(shortPlay(1)))),
        )
        val viewModel = ShortPlayCategoryViewModel(
            repository, parents = tree, initialTypeId = 23, pageSize = 2,
        )

        viewModel.selectParent(31)

        val state = viewModel.state.value
        assertEquals(31, state.selectedParentId)
        assertEquals(ALL_CATEGORY_ID, state.selectedChildId)
        assertEquals(listOf(32), state.children.map { it.id })
        assertEquals(ShortPlayFeedCall(31, 1), repository.calls.last())
    }

    @Test
    fun `switching sub category queries that id and all goes back to the parent`() = runTest {
        val repository = ShortPlayTestRepository(
            pages = mutableMapOf(1 to AppResult.Success(listOf(shortPlay(1)))),
        )
        val viewModel = ShortPlayCategoryViewModel(
            repository, parents = tree, initialTypeId = 21, pageSize = 2,
        )

        viewModel.selectChild(22)
        assertEquals(ShortPlayFeedCall(22, 1), repository.calls.last())
        viewModel.selectChild(ALL_CATEGORY_ID)
        assertEquals(ShortPlayFeedCall(21, 1), repository.calls.last())
    }

    @Test
    fun `sub category outside the current parent is ignored`() = runTest {
        val repository = ShortPlayTestRepository(
            pages = mutableMapOf(1 to AppResult.Success(listOf(shortPlay(1)))),
        )
        val viewModel = ShortPlayCategoryViewModel(
            repository, parents = tree, initialTypeId = 21, pageSize = 2,
        )

        // 32 是另一个父分类的子分类：当前父分类下没有它，不该把列表换掉
        viewModel.selectChild(32)

        assertEquals(21, viewModel.state.value.selectedParentId)
        assertEquals(ALL_CATEGORY_ID, viewModel.state.value.selectedChildId)
        assertEquals(1, repository.calls.size)
    }

    @Test
    fun `single parent hides the parent row and shows its subs`() = runTest {
        val repository = ShortPlayTestRepository(pages = mutableMapOf(1 to AppResult.Success(emptyList())))
        val single = listOf(tree.first())
        val viewModel = ShortPlayCategoryViewModel(
            repository, parents = single, initialTypeId = 21, pageSize = 2,
        )

        val state = viewModel.state.value
        assertFalse(state.showParents)
        assertTrue(state.showChildren)
    }

    @Test
    fun `parent without subs hides the sub row and queries the parent`() = runTest {
        val repository = ShortPlayTestRepository(pages = mutableMapOf(1 to AppResult.Success(emptyList())))
        val leaves = listOf(ShortPlayType(41, "仙侠"), ShortPlayType(42, "古装"))
        val viewModel = ShortPlayCategoryViewModel(
            repository, parents = leaves, initialTypeId = 42, pageSize = 2,
        )

        val state = viewModel.state.value
        assertTrue(state.showParents)
        assertFalse(state.showChildren)
        assertEquals(listOf(ShortPlayFeedCall(42, 1)), repository.calls)
    }

    @Test
    fun `empty tree flags the notice and still falls back to the default feed`() = runTest {
        val repository = ShortPlayTestRepository(
            pages = mutableMapOf(1 to AppResult.Success(listOf(shortPlay(1)))),
        )
        val viewModel = ShortPlayCategoryViewModel(
            repository, parents = emptyList(), initialTypeId = 0, pageSize = 2,
        )

        val state = viewModel.state.value
        assertTrue(state.isEmptyTree)
        assertFalse(state.showParents)
        assertFalse(state.showChildren)
        // typeId 传 null：跟着仓库的后台默认分类走，页面不会因为没分类变成空屏
        assertEquals(listOf(ShortPlayFeedCall(null, 1)), repository.calls)
    }

    @Test
    fun `load more appends deduped items`() = runTest {
        val repository = ShortPlayTestRepository(
            pages = mutableMapOf(
                1 to AppResult.Success(listOf(shortPlay(1), shortPlay(2))),
                2 to AppResult.Success(listOf(shortPlay(2), shortPlay(3))),
            ),
        )
        val viewModel = ShortPlayCategoryViewModel(
            repository, parents = tree, initialTypeId = 21, pageSize = 2,
        )

        viewModel.loadMore()

        assertEquals(listOf(1, 2, 3), viewModel.state.value.items.map { it.id })
        assertEquals(2, viewModel.state.value.page)
    }
}
