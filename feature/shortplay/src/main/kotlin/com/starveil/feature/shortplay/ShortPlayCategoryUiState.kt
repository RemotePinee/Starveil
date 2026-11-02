package com.starveil.feature.shortplay

import androidx.compose.runtime.Immutable
import com.starveil.core.model.ShortPlay
import com.starveil.core.model.ShortPlayType

/**
 * 短剧分类页状态：一级分类（父）+ 二级分类（子）+ 短剧卡片网格。
 *
 * 两级关系来自后台下发的分类树（macCMS `mac_type.type_pid`），客户端只做联动展示：
 * 选了一级就展示它的子分类，一级选「全部」时把所有父分类的子分类平铺合并（只是展示，不改树、
 * 不新增、不改名）。
 */
@Immutable
data class ShortPlayCategoryUiState(
    /** 一级分类：分类树的父分类，顺序即后台下发的顺序。 */
    val parents: List<ShortPlayType> = emptyList(),
    /** 当前一级分类 id；[ALL_CATEGORY_ID] 表示一级选了「全部」（不限父分类）。 */
    val selectedParentId: Int = 0,
    /** 当前父分类的直接子分类，不含虚拟的「全部」；一级选「全部」时为空——展示走 [visibleChildren]。 */
    val children: List<ShortPlayType> = emptyList(),
    /** 二级选中：0 = 「全部」，即按父分类查（服务端会带上它的全部子分类）。 */
    val selectedChildId: Int = 0,
    val items: List<ShortPlay> = emptyList(),
    /** 已拿到的最后一页；下一页从它 +1 开始。 */
    val page: Int = 0,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    /** 还有下一页。后端一页不满时置 false，触底就不再请求。 */
    val hasMore: Boolean = true,
    val error: String? = null,
) {
    /** 只有一个父分类时顶部那一行隐藏：一条只有一个选项的 tab 没有意义。 */
    val showParents: Boolean
        get() = parents.size > 1

    /**
     * 二级行实际展示的子分类。
     *
     * 一级选了「全部」时不是"没有二级"：把每个父分类的子分类按父分类顺序平铺合并，用户仍然要能
     * 在「全部」下按子分类筛选。按 id 去重、保留先出现的那个——两个父分类下挂了同名子分类时，
     * 它们是两个真实分类（不同 id、各有各的内容），按名字去重会把其中一组的内容藏掉。
     */
    val visibleChildren: List<ShortPlayType>
        get() = if (selectedParentId == ALL_CATEGORY_ID) {
            parents.flatMap { parent -> parent.children }.distinctBy { child -> child.id }
        } else {
            children
        }

    /** 二级行只在有子分类可展示时出现：老配置只填了叶子分类时不留一条只有「全部」的条。 */
    val showChildren: Boolean
        get() = visibleChildren.isNotEmpty()

    /** 分类树为空（后台没配）：给一句提示，网格仍按仓库默认分类加载。 */
    val isEmptyTree: Boolean
        get() = parents.isEmpty()

    /**
     * 单分类档实际请求的分类，三档降级：
     *
     * 1. 二级选了子分类 → 子分类 id；
     * 2. 二级是「全部」→ 父分类 id（服务端查父分类会带上它的全部子分类）；
     * 3. 一级也是「全部」→ 0，但这一档**不再走本属性**：它改走 [queryTypeIds] 的跨父分类聚合。
     *    只有在分类树为空、[queryTypeIds] 也拿不到 id 时才落回 0，由仓库换成后台默认短剧分类。
     *
     * 第 3 档曾经是"按后台默认短剧分类出一屏"：后端 `type=0` 是不加分类条件，会把全站影片
     * 混进来，所以 `DefaultShortPlayRepository.resolveTypeId` 把 0 换成一个确定的短剧分类——
     * 宁可窄一点，也不能让剧场页退化成全站混排。
     */
    val queryTypeId: Int
        get() = selectedChildId.takeIf { it > 0 } ?: selectedParentId

    /**
     * 聚合档的请求分类：一级、二级都选「全部」时，所有父分类的 id。
     *
     * 后端按 `type_ids` 取并集，并且**每个父分类仍然带上自己的全部直属子分类**（与单选
     * 父分类同一套规则），所以「全部」看到的是全站短剧，而不是以前那一个默认分类。
     * 其它档位返回空列表，调用方改看 [queryTypeId]；分类树为空时也是空列表，页面照旧落回
     * 仓库默认分类，不会因为没配分类变成空屏。
     */
    val queryTypeIds: List<Int>
        get() = if (selectedParentId == ALL_CATEGORY_ID && selectedChildId == ALL_CATEGORY_ID) {
            parents.map { parent -> parent.id }.filter { id -> id > 0 }
        } else {
            emptyList()
        }

    /** 请求成功但一条都没有：给一句提示，不是整页错误态。 */
    val isEmpty: Boolean
        get() = !isLoading && !isLoadingMore && error == null && items.isEmpty()
}

/** 分类页动作。参数超过 5 个会被 detekt 拒，所以收成一个对象。 */
@Immutable
data class ShortPlayCategoryActions(
    val onSelectParent: (Int) -> Unit = {},
    val onSelectChild: (Int) -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onRetry: () -> Unit = {},
    /** 「换一批」：重新拉当前分类第一页。 */
    val onRefresh: () -> Unit = {},
    val onPlay: (ShortPlay) -> Unit = {},
    val onBack: () -> Unit = {},
)

/** 客户端虚拟的「全部」：id 固定 0，不占后端任何分类 id。一级、二级共用。 */
internal const val ALL_CATEGORY_ID = 0

/** 两级分类行首项「全部」的文案。 */
internal const val ALL_CATEGORY_LABEL = "全部"
