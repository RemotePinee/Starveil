package com.starveil.feature.shortplay

import com.starveil.core.common.AppResult
import com.starveil.core.model.ShortPlayType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 一级「全部」这一档：二级行不收起，改成把所有父分类的子分类按父分类顺序平铺合并。
 *
 * 单独一个类而不是塞进 [ShortPlayCategoryViewModelTest]：那边讲"选了父分类看它的子分类"，
 * 这里讲"一级选了「全部」"，合在一起会把类撑过 detekt 的 200 行阈值。
 */
class ShortPlayCategoryAllOptionTest {
    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val tree = listOf(
        ShortPlayType(21, "红果", listOf(ShortPlayType(22, "都市豪门"), ShortPlayType(23, "逆袭爽剧"))),
        ShortPlayType(31, "微短剧", listOf(ShortPlayType(32, "甜宠"))),
    )

    @Test
    fun `all on the parent row flattens every parent subs and asks for the default feed`() = runTest {
        val repository = ShortPlayTestRepository(
            pages = mutableMapOf(1 to AppResult.Success(listOf(shortPlay(1)))),
        )
        val viewModel = ShortPlayCategoryViewModel(
            repository, parents = tree, initialTypeId = 21, pageSize = 2,
        )

        viewModel.selectParent(ALL_CATEGORY_ID)

        val state = viewModel.state.value
        assertEquals(ALL_CATEGORY_ID, state.selectedParentId)
        assertEquals(ALL_CATEGORY_ID, state.selectedChildId)
        // children 是"当前父分类的直接子分类"，一级是「全部」时留空；展示走 visibleChildren
        assertTrue(state.children.isEmpty())
        // 平铺顺序 = 父分类顺序 × 各自子分类顺序
        assertEquals(listOf(22, 23, 32), state.visibleChildren.map { it.id })
        assertTrue(state.showParents)
        // 一级「全部」也保留二级行：用户还能按子分类筛选
        assertTrue(state.showChildren)
        // 两级都是「全部」：把每个父分类的 id 一起下发（后端 `type_ids` 取并集）
        assertEquals(listOf(listOf(21, 31)), repository.typeIdsCalls)
        // 切换后不再有单分类请求：init 那一发（父分类 21）之后只走了聚合档
        assertEquals(listOf(ShortPlayFeedCall(21, 1)), repository.calls)
    }

    @Test
    fun `sub category under all parents queries that sub`() = runTest {
        val repository = ShortPlayTestRepository(
            pages = mutableMapOf(1 to AppResult.Success(listOf(shortPlay(1)))),
        )
        val viewModel = ShortPlayCategoryViewModel(
            repository, parents = tree, initialTypeId = 21, pageSize = 2,
        )

        viewModel.selectParent(ALL_CATEGORY_ID)
        // 32 挂在一级行没选中的那个父分类下：一级「全部」时它也是可选项
        viewModel.selectChild(32)

        val state = viewModel.state.value
        assertEquals(ALL_CATEGORY_ID, state.selectedParentId)
        assertEquals(32, state.selectedChildId)
        assertEquals(ShortPlayFeedCall(32, 1), repository.calls.last())
    }

    @Test
    fun `flattened subs dedupe by id and keep same named ones`() = runTest {
        val repository = ShortPlayTestRepository(pages = mutableMapOf(1 to AppResult.Success(emptyList())))
        val overlapping = listOf(
            ShortPlayType(51, "红果", listOf(ShortPlayType(61, "甜宠"))),
            ShortPlayType(52, "微短剧", listOf(ShortPlayType(61, "甜宠"), ShortPlayType(62, "甜宠"))),
        )
        val viewModel = ShortPlayCategoryViewModel(
            repository, parents = overlapping, initialTypeId = 51, pageSize = 2,
        )

        viewModel.selectParent(ALL_CATEGORY_ID)

        // 61 在两个父分类下都出现：按 id 去重只留先出现的那一个
        // 62 与 61 同名但 id 不同——两个真实分类，各有一批内容，不能按名字合并
        assertEquals(listOf(61, 62), viewModel.state.value.visibleChildren.map { it.id })
    }
}
