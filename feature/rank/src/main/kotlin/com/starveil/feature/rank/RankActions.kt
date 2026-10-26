package com.starveil.feature.rank

/**
 * 榜单页回调集合。
 *
 * 收成一个不可变对象是为了满足"参数不超过 5 个"（AGENTS.md 第 2 节），
 * 也让纯 UI 的 [RankScreen] 不必依赖 ViewModel。
 */
@androidx.compose.runtime.Immutable
class RankActions(
    val onCategorySelect: (Int) -> Unit = {},
    val onOrderSelect: (RankOrder) -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onVodClick: (Int) -> Unit = {},
)
