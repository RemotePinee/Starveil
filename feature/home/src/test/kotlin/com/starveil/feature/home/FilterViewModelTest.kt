package com.starveil.feature.home

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.data.VodRepository
import com.starveil.core.model.TypeExtend
import com.starveil.core.model.Vod
import com.starveil.core.model.VodOrder
import com.starveil.core.model.VodType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 片库筛选页状态测试。
 *
 * 守四条契约：默认「全部」、换分类重新请求、`extend` 为空不渲染筛选行、
 * 空结果是空态而不是错误态。桩数据与 fake 在文件末尾。
 */
class FilterViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    @Test
    fun `first entry uses all when the caller gives no type`() {
        val repository = FakeFilterRepository(firstPage = AppResult.Success(listOf(vod(1))))

        val state = FilterViewModel(repository, initialTypeId = 0).state.value

        assertEquals(0, state.selection.typeId)
        assertEquals(FILTER_ALL, state.selection.area)
        // 分类行首项是「全部」，默认选中它（值是 type 0）
        assertEquals(FilterKind.TYPE, state.rows.first().kind)
        assertEquals("0", state.rows.first().selected)
        // 「全部」排序在仓库层落回后端缺省值 time
        assertEquals(VodOrder.DEFAULT, repository.calls.single().order)
    }

    @Test
    fun `first entry keeps the type id handed in by the caller`() {
        val repository = FakeFilterRepository(firstPage = AppResult.Success(listOf(vod(1))))

        val state = FilterViewModel(repository, initialTypeId = 2).state.value

        assertEquals(2, state.selection.typeId)
        assertEquals(2, repository.calls.single().type)
    }

    @Test
    fun `switching type requests page one again and clears the extend selections`() {
        val repository = FakeFilterRepository(firstPage = AppResult.Success(listOf(vod(1))))
        val viewModel = FilterViewModel(repository, initialTypeId = 0)

        viewModel.onSelect(FilterKind.AREA, "大陆")
        viewModel.onSelect(FilterKind.TYPE, "2")

        val last = repository.calls.last()
        assertEquals(2, last.type)
        assertEquals(1, last.page)
        // 新分类的 extend 里没有"大陆"，旧选值必须丢掉，否则会筛出空结果
        assertEquals(FILTER_ALL, last.area)
        assertEquals(FILTER_ALL, viewModel.state.value.selection.area)
    }

    @Test
    fun `empty extend renders only the type and order rows`() {
        val repository = FakeFilterRepository(
            typeList = AppResult.Success(
                listOf(
                    VodType(id = 1, name = "电影"),
                    VodType(id = 2, name = "电视剧", extend = TypeExtend()),
                ),
            ),
            firstPage = AppResult.Success(listOf(vod(1))),
        )

        val state = FilterViewModel(repository, initialTypeId = 1).state.value

        assertEquals(listOf(FilterKind.TYPE, FilterKind.ORDER), state.rows.map { it.kind })
    }

    @Test
    fun `extend drives the area year class and language rows`() {
        val repository = FakeFilterRepository(
            typeList = AppResult.Success(
                listOf(
                    VodType(
                        id = 1,
                        name = "电影",
                        extend = TypeExtend(
                            area = listOf("大陆", "香港"),
                            year = listOf("2026"),
                            classNames = listOf("喜剧"),
                            lang = listOf("国语"),
                        ),
                    ),
                ),
            ),
            firstPage = AppResult.Success(listOf(vod(1))),
        )

        val state = FilterViewModel(repository, initialTypeId = 1).state.value

        assertEquals(
            listOf(
                FilterKind.TYPE,
                FilterKind.AREA,
                FilterKind.YEAR,
                FilterKind.CLASS,
                FilterKind.LANG,
                FilterKind.ORDER,
            ),
            state.rows.map { it.kind },
        )
        // 每行首项都是「全部」：分类行是 type 0，其余维度是空串
        assertTrue(state.rows.all { it.options.first().label == "全部" })
        assertEquals("", state.rows[1].options.first().value)
    }

    @Test
    fun `empty result is an empty state not an error`() {
        val repository = FakeFilterRepository(firstPage = AppResult.Success(emptyList()))

        val state = FilterViewModel(repository, initialTypeId = 0).state.value

        assertTrue(state.items.isEmpty())
        assertTrue(state.isEmpty)
        assertNull(state.error)
    }

    @Test
    fun `request failure with no result becomes a readable error`() {
        val repository = FakeFilterRepository(firstPage = AppResult.Failure(AppError.Network(null)))

        val state = FilterViewModel(repository, initialTypeId = 0).state.value

        assertFalse(state.isEmpty)
        assertEquals("网络连接失败，请检查网络后重试", state.error)
    }

    @Test
    fun `load more appends the next page and stops at the last one`() {
        // 第一页满 20 条 -> 还有下一页；第二页只有 1 条 -> 到底
        val firstPage = (1..VodRepository.DEFAULT_PAGE_SIZE).map { vod(it) }
        val repository = FakeFilterRepository(
            firstPage = AppResult.Success(firstPage),
            laterPages = mapOf(2 to listOf(vod(99))),
        )
        val viewModel = FilterViewModel(repository, initialTypeId = 0)

        viewModel.loadMore()
        val afterSecondPage = viewModel.state.value
        viewModel.loadMore()

        assertEquals(VodRepository.DEFAULT_PAGE_SIZE + 1, afterSecondPage.items.size)
        assertFalse(afterSecondPage.hasMore)
        assertEquals(2, repository.calls.last().page)
    }
}

/** 一次 [VodRepository.filter] 调用的快照，断言用。 */
private data class FilterCall(
    val type: Int,
    val page: Int,
    val area: String,
    val order: String,
)

/**
 * 只实现筛选页用到的方法。
 *
 * 覆写 [VodRepository.filter]（接口里有默认实现）而不是 [VodRepository.list]：
 * 页面真正下发的是前者，只有在这里记录才能看到筛选参数。
 *
 * 默认给一个一级分类：真实后端至少回一条，筛选条才有「分类」行可断言。
 */
private class FakeFilterRepository(
    private val typeList: AppResult<List<VodType>> =
        AppResult.Success(listOf(VodType(id = 1, name = "电影"))),
    private val firstPage: AppResult<List<Vod>> = AppResult.Success(emptyList()),
    private val laterPages: Map<Int, List<Vod>> = emptyMap(),
) : VodRepository {

    val calls = mutableListOf<FilterCall>()

    override suspend fun list(type: Int, page: Int, limit: Int): AppResult<List<Vod>> =
        AppResult.Success(emptyList())

    override suspend fun detail(vodId: Int): AppResult<Vod> = AppResult.Failure(AppError.Unknown(null))

    override suspend fun types(): AppResult<List<VodType>> = typeList

    override suspend fun filter(
        type: Int,
        page: Int,
        limit: Int,
        area: String,
        year: String,
        lang: String,
        className: String,
        order: String,
    ): AppResult<List<Vod>> {
        calls += FilterCall(type = type, page = page, area = area, order = order)
        if (page == FIRST_PAGE) return firstPage
        return AppResult.Success(laterPages[page].orEmpty())
    }
}

private fun vod(id: Int) = Vod(
    id = id,
    name = "影片$id",
    poster = null,
    remarks = null,
    year = null,
    area = null,
    score = null,
    blurb = null,
    playSources = emptyList(),
)

/** 第一页页码，与 `VodRepository.filter` 的默认值一致。 */
private const val FIRST_PAGE = 1
