package com.starveil.feature.home

import androidx.compose.runtime.Immutable
import com.starveil.core.model.TypeExtend
import com.starveil.core.model.Vod
import com.starveil.core.model.VodOrder
import com.starveil.core.model.VodType

/** 「全部」的取值：每个维度都用空串表示"不限"，与后端缺省语义一致。 */
const val FILTER_ALL = ""

/**
 * 筛选条的种类。枚举顺序就是页面上的渲染顺序：
 * 分类 → 排序 → 地区 → 年份 → 题材 → 语言。
 */
enum class FilterKind { TYPE, SUBTYPE, ORDER, AREA, YEAR, CLASS, LANG }

/** 一个可点的筛选项。[value] 为 [FILTER_ALL] 时表示"全部/不限"。 */
@Immutable
data class FilterOption(val label: String, val value: String)

/** 一行筛选条：种类 + 候选 + 当前选中值（存值不存下标，候选变化时才不会错位）。 */
@Immutable
data class FilterRow(
    val kind: FilterKind,
    val options: List<FilterOption>,
    val selected: String,
)

/**
 * 当前筛选条件，字段与 `v1.vod` 的 query 一一对应。
 *
 * 空串 = 不限；[order] 的空串由仓库层落回 `time`（见 [VodOrder.sanitize]）。
 */
@Immutable
data class FilterSelection(
    val typeId: Int = 0,
    val order: String = FILTER_ALL,
    val area: String = FILTER_ALL,
    val year: String = FILTER_ALL,
    val className: String = FILTER_ALL,
    val lang: String = FILTER_ALL,
)

/** 片库筛选页状态。不可变，通过 `StateFlow` 暴露（AGENTS.md 第 5 节）。 */
@Immutable
data class FilterUiState(
    val selection: FilterSelection = FilterSelection(),
    val rows: List<FilterRow> = emptyList(),
    val items: List<Vod> = emptyList(),
    /** 已拿到的最后一页；下一页从它 +1 开始。 */
    val loadedPage: Int = 0,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    /** 还有下一页。后端一页不满时置 false，触底就不再请求。 */
    val hasMore: Boolean = true,
    val error: String? = null,
) {
    /** 请求成功但一条都没有：页面给一句提示，不是整页错误态。 */
    val isEmpty: Boolean
        get() = !isBusy && error == null && items.isEmpty()

    /** 首屏或翻页正在跑：两种加载态对页面是同一件事（都别给空态/错误态）。 */
    private val isBusy: Boolean
        get() = isLoading || isLoadingMore

}

/** 页面动作。参数超过 5 个会被 detekt 拒，所以收成一个对象（同 [SearchActions]）。 */
@Immutable
data class FilterActions(
    val onSelect: (FilterKind, String) -> Unit = { _, _ -> },
    val onLoadMore: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onSearch: () -> Unit = {},
    val onVodClick: (Vod) -> Unit = {},
)

/**
 * 由分类列表与当前选值算出筛选条。
 *
 * 两条渲染规则：
 * 1. 候选为空的行整行不渲染——分类没配这一维（`extend` 缺键或为空）就少一行，不留空条；
 * 2. 每行首项是「全部」，唯一例外是排序："不限"对排序没有意义，
 *    的「全部」等于后端缺省顺序（最新）。
 */
internal fun buildFilterRows(types: List<VodType>, selection: FilterSelection): List<FilterRow> {
    val current = types.firstOrNull { it.id == selection.typeId }
    val roots = types.filter { it.parentId == 0 }
    // 分类行点亮谁：选中子分类时点亮它的父分类，选中「全部」时点亮「全部」
    val typeSelected = typeSelectedOf(current)
    // 二级分类：选了某个一级分类就只列它的子分类；选「全部」时把所有二级分类都摆出来
    val subtypes = if (typeSelected > 0) {
        types.filter { it.parentId == typeSelected }
    } else {
        types.filter { it.parentId > 0 }
    }
    // 二级行的「全部」：选了一级分类就等于"这个父分类下不细分"，全局「全部」时是 0
    val subtypeAll = if (typeSelected > 0) typeSelected.toString() else ALL_TYPE_VALUE
    val subtypeSelected = if (selection.typeId == 0) subtypeAll else selection.typeId.toString()
    // 初始「全部」没有对应分类，但 macCMS 的筛选词是挂在分类上的：借用第一个一级分类
    val extend = (current ?: roots.firstOrNull())?.extend ?: TypeExtend()
    return buildList {
        if (roots.isNotEmpty()) add(rootTypeRow(roots, typeSelected))
        addSubtypeRow(subtypes, subtypeAll, subtypeSelected)
        addExtendRows(extend, selection)
        // 排序放最后一行：它改的是「怎么排」，不是「筛什么」，贴在海报墙上方更顺手
        add(orderRow(selection.order))
    }
}

/** 分类行该点亮哪个 id：子分类归到父分类，未选中为 0。 */
private fun typeSelectedOf(current: VodType?): Int = when {
    current == null -> 0
    current.parentId > 0 -> current.parentId
    else -> current.id
}

/** 地区/年份/题材/语言四行；对应键为空时 [addExtendRow] 自己会跳过。 */
private fun MutableList<FilterRow>.addExtendRows(extend: TypeExtend, selection: FilterSelection) {
    addExtendRow(FilterKind.AREA, extend.area, selection.area)
    addExtendRow(FilterKind.YEAR, extend.year, selection.year)
    addExtendRow(FilterKind.CLASS, extend.classNames, selection.className)
    addExtendRow(FilterKind.LANG, extend.lang, selection.lang)
}

/** 点某一项后的新条件。换分类时清空后四行：它们的候选来自新分类的 `extend`。 */
internal fun FilterSelection.select(kind: FilterKind, value: String): FilterSelection = when (kind) {
    FilterKind.TYPE, FilterKind.SUBTYPE -> copy(
        typeId = value.toIntOrNull() ?: 0,
        area = FILTER_ALL,
        year = FILTER_ALL,
        className = FILTER_ALL,
        lang = FILTER_ALL,
    )

    FilterKind.ORDER -> copy(order = value)
    FilterKind.AREA -> copy(area = value)
    FilterKind.YEAR -> copy(year = value)
    FilterKind.CLASS -> copy(className = value)
    FilterKind.LANG -> copy(lang = value)
}

/** 一级分类行：只列 `parentId == 0` 的父分类。 */
private fun rootTypeRow(roots: List<VodType>, selectedId: Int): FilterRow = FilterRow(
    kind = FilterKind.TYPE,
    options = listOf(FilterOption(ALL_LABEL, ALL_TYPE_VALUE)) +
        roots.map { FilterOption(it.name, it.id.toString()) },
    selected = selectedId.toString(),
)

/**
 * 子分类行：当前父分类下面的细分（动作片/喜剧片/国产剧…），父分类没有子分类时整行不出现。
 *
 * 「全部」的取值是父分类 id 而不是 [FILTER_ALL]：选它表示「这个父分类下不再细分」，
 * 回 0 会把父分类一起丢掉。
 */
private fun MutableList<FilterRow>.addSubtypeRow(
    children: List<VodType>,
    allValue: String,
    selected: String,
) {
    if (children.isEmpty()) return
    add(
        FilterRow(
            kind = FilterKind.SUBTYPE,
            options = listOf(FilterOption(ALL_LABEL, allValue)) +
                children.map { FilterOption(it.name, it.id.toString()) },
            selected = selected,
        ),
    )
}

private fun orderRow(selected: String): FilterRow = FilterRow(
    kind = FilterKind.ORDER,
    options = listOf(
        FilterOption(ALL_LABEL, FILTER_ALL),
        FilterOption("最热", VodOrder.HITS),
        FilterOption("评分", VodOrder.SCORE),
        FilterOption("月榜", VodOrder.HITS_MONTH),
    ),
    selected = selected,
)

private fun MutableList<FilterRow>.addExtendRow(
    kind: FilterKind,
    values: List<String>,
    selected: String,
) {
    if (values.isEmpty()) return
    add(
        FilterRow(
            kind = kind,
            options = listOf(FilterOption(ALL_LABEL, FILTER_ALL)) + values.map { FilterOption(it, it) },
            selected = selected,
        ),
    )
}

private const val ALL_LABEL = "全部"

/** 分类行的「全部」= type 0（后端按全部返回）。 */
private const val ALL_TYPE_VALUE = "0"
