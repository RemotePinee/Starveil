package com.starveil.feature.rank

import com.starveil.core.model.Vod

/**
 * 榜单页状态。不可变，通过 StateFlow 暴露（AGENTS.md 第 5 节）。
 *
 * 两个接口的状态分开持有，不做归一：
 * - [catalog] 来自 `v1.vod/vodPhbAll`，只负责分类目录；
 * - [board] 来自 `v1.vod/vodphb`，只负责当前选中分类的影片列表
 *
 * [order] 与 [selectedTypeId] 都是列表请求的必要参数（`vodphb` 的 order 与 type），
 * 放进状态是为了让"顶部分段选中的维度"和"实际发出的请求"永远是同一个值。
 */
data class RankUiState(
    val catalog: RankCatalogState = RankCatalogState.Loading,
    val selectedTypeId: Int? = null,
    val order: RankOrder = RankOrder.DAY,
    val board: RankBoardState = RankBoardState.Idle,
)

/**
 * 分类目录状态。
 *
 * 空目录与请求失败必须区分：历史版本目录请求失败只弹一次提示，Tab 留在上一次的结果上
 * 用户无法判断是"没数据"还是"没请求成功"。
 */
sealed interface RankCatalogState {

    data object Loading : RankCatalogState

    data object Empty : RankCatalogState

    data class Error(val message: String) : RankCatalogState

    data class Content(val categories: List<RankCategory>) : RankCatalogState
}

/**
 * 分类标签只需要 id 与名字。
 *
 * `RankGroup.typeImage` 旧榜单页没有使用（只取了 type_id/type_name），
 * 所以不进入 UI 模型，避免页面误用后端字段名。
 */
data class RankCategory(
    val typeId: Int,
    val name: String,
)

/**
 * 榜单列表状态。
 *
 * [Content.appendError] 与 [Content.items] 并存：翻页失败不清空已有列表，只在底部给重试入口。
 * 历史版本 `onError` 只打印堆栈，列表停在半截且没有任何提示。
 */
sealed interface RankBoardState {

    /** 目录还没拿到，尚未发起列表请求。 */
    data object Idle : RankBoardState

    /** 首页加载中，或切换分类后等待新分类数据。 */
    data object Loading : RankBoardState

    /** 接口成功但当前分类没有影片。 */
    data object Empty : RankBoardState

    /** 首页请求失败，没有可展示的列表。 */
    data class Error(val message: String) : RankBoardState

    data class Content(
        val items: List<Vod>,
        val page: Int,
        val isAppending: Boolean = false,
        val endReached: Boolean = false,
        val appendError: String? = null,
        /**
         * 换维度/换分类后正在拉新的一页。
         *
         * 这一位存在的意义就是"别把列表清空、别退回整屏转圈"：旧榜单一屏还在，
         * 顶部叠一条细进度条，新数据到了整批换掉。
         */
        val isRefreshing: Boolean = false,
    ) : RankBoardState
}

/**
 * 保留已有列表、只打上"正在换数据"的标记；还没有列表时仍回到 Loading。
 *
 * 换维度/换分类走这条路，页面就不会整屏退回转圈——旧榜单一屏还在，顶部叠一条细进度条。
 */
internal fun RankBoardState.refreshing(): RankBoardState =
    if (this is RankBoardState.Content) {
        copy(page = 1, endReached = false, isRefreshing = true)
    } else {
        RankBoardState.Loading
    }

/**
 * 排序串。历史版本由榜单弹窗传入的整数映射：
 * 2 = 月榜、3 = 周榜、4 = 日榜，其余为总榜。
 *
 * [label] 只给顶部分段控件用；页面不再自己拼"日/周/月"字面量。
 */
enum class RankOrder(val value: String, val label: String) {
    ALL("vod_hits desc", "总榜"),
    MONTH("vod_hits_month desc", "月榜"),
    WEEK("vod_hits_week desc", "周榜"),
    DAY("vod_hits_day desc", "日榜"),
}
