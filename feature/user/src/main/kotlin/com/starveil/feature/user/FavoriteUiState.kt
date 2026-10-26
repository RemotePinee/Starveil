package com.starveil.feature.user

import com.starveil.core.common.AppError
import com.starveil.core.model.FavoriteItem

/**
 * 收藏页状态（列表 `GET v1.user/favs`）。
 *
 * 分页口径照搬：页码从 1 开始，触底先自增再拉，
 * 第 1 页覆盖、后续页追加（`:169-184`）。后端响应里的 `total` 没有进 `FavoriteItem`，
 * 所以"还有没有下一页"只能靠这一页是否被填满推断，见 [hasMore]。
 *
 * @param loadedPage 已成功落地的页码；0 表示一页都没成，此时不允许触底加载
 * @param needsLogin 会话已失效（`AuthRepository.restore()` 返回 null），UI 改走登录引导
 * @param message 一次性提示（取消收藏失败、翻页失败），UI 弹完 Toast 后清空
 * @param removingIds 正在请求取消的影片 id，用于禁用该行按钮，避免连点重复删除
 */
data class FavoriteUiState(
    val items: List<FavoriteItem> = emptyList(),
    val loadedPage: Int = 0,
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val errorMessage: String? = null,
    val message: String? = null,
    val needsLogin: Boolean = false,
    val removingIds: Set<Int> = emptySet(),
) {

    /** 空态：一页成功返回且没有条目。加载中、错误、未登录都不算空。 */
    val isEmpty: Boolean
        get() = items.isEmpty() && !isInitialLoading && errorMessage == null && !needsLogin

    /** 首屏失败：整页错误态，带重试。 */
    val isError: Boolean
        get() = errorMessage != null && items.isEmpty()

    /** 任一路请求在飞时不再接受翻页：没有这道闸门，快速滑动会并发翻页。 */
    val isBusy: Boolean
        get() = isInitialLoading || isRefreshing || isLoadingMore

    val canLoadMore: Boolean
        get() = hasMore && loadedPage > 0 && !isBusy
}

/**
 * 错误 → 用户可读文案。
 *
 * 不复用 `LoginViewModel.toUserMessage()`：那边的 Business 兜底是"登录失败"，
 * 用在收藏 / 资料页会误导。两处都只做展示，不参与重试判定。
 */
internal fun AppError.toPageMessage(): String = when (this) {
    is AppError.Business -> message.ifBlank { "请求被拒绝，请稍后重试" }
    is AppError.Network -> "网络异常，请检查网络后重试"
    is AppError.Http -> "服务异常（$code）"
    is AppError.Serialization -> "数据解析失败，请稍后重试"
    is AppError.Unknown -> "未知错误，请稍后重试"
}
