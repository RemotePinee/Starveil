package com.starveil.feature.shortplay

import androidx.compose.runtime.Immutable
import com.starveil.core.model.ShortPlay
import com.starveil.core.model.ShortPlayType

/**
 * 短剧分类页状态：一级分类（父）+ 二级分类（子）+ 短剧卡片网格。
 *
 * 两级关系来自后台下发的分类树（macCMS `mac_type.type_pid`），客户端只做联动展示，
 * 不合并、不新增、不改名。
 */
@Immutable
data class ShortPlayCategoryUiState(
    /** 一级分类：分类树的父分类，顺序即后台下发的顺序。 */
    val parents: List<ShortPlayType> = emptyList(),
    /** 当前一级分类 id；只有一个父分类时它仍有值，只是顶部那一行不显示。 */
    val selectedParentId: Int = 0,
    /** 当前父分类的直接子分类，不含虚拟的「全部」。 */
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

    /** 没有子分类时二级行不出现：老配置只填了叶子分类时不该留一条只有「全部」的条。 */
    val showChildren: Boolean
        get() = children.isNotEmpty()

    /** 分类树为空（后台没配）：给一句提示，网格仍按仓库默认分类加载。 */
    val isEmptyTree: Boolean
        get() = parents.isEmpty()

    /** 实际请求的分类：选了子分类用它，否则用父分类（= 全部）；0 表示跟着仓库默认走。 */
    val queryTypeId: Int
        get() = selectedChildId.takeIf { it > 0 } ?: selectedParentId

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

/** 客户端虚拟的「全部」：id 固定 0，不占后端任何分类 id。 */
internal const val ALL_CATEGORY_ID = 0

/** 二级分类行首项的文案。 */
internal const val ALL_CATEGORY_LABEL = "全部"
