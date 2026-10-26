package com.starveil.feature.detail

import com.starveil.core.model.Comment

/**
 * 评论区状态。
 *
 * 单独一条流而不是塞进 [DetailUiState.Content]：评论区失败只影响评论区块，
 * 不会把整页打成错误态（旧页把评论和详情塞在同一个 Fragment 里，
 * 评论拉不到时 是空 `onError`，页面照常显示）。
 *
 * 与的差异：旧页点「查看更多」跳评论列表 Activity，本页同页展开——
 * 详情页的评论是附加内容，跳走会丢掉当前播放源与选集位置。
 *
 * @param items 已加载的评论，按后端顺序（新在前）
 * @param page 已加载到的页码，从 1 开始
 * @param total 本片评论总数（后端 `data.total`）；未知时为 0
 * @param isLoading 第一页在飞；首屏用整块占位，重试按钮据此互斥
 * @param isLoadingMore 追加页在飞；已有列表保留，只在底部转圈
 * @param error 第一页失败时的可读文案；null 表示没有失败。追加页失败只弹提示，不写这里
 * @param posting 发布请求在飞；输入框与发送按钮禁用，避免连点触发后端 30 秒冷却
 * @param postedCount 发布成功计数：输入框据此清空草稿，提示走 [DetailEvent.CommentPosted]
 * @param isExpanded 是否同页展开全部；展开后才会自动续拉下一页
 */
data class CommentSectionState(
    val items: List<Comment> = emptyList(),
    val page: Int = FIRST_COMMENT_PAGE,
    val total: Int = 0,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val posting: Boolean = false,
    val postedCount: Int = 0,
    val isExpanded: Boolean = false,
) {

    /** 当前要渲染的评论：收起态只给前 [COMMENT_PREVIEW_LIMIT] 条。 */
    val visibleItems: List<Comment>
        get() = if (isExpanded) items else items.take(COMMENT_PREVIEW_LIMIT)

    /** 「查看全部 N 条」里的 N；总数未知（0）时不显示这个入口。 */
    val hiddenCount: Int
        get() = (total - COMMENT_PREVIEW_LIMIT).coerceAtLeast(0)

    /** 还有没加载的评论。total 未知时按 0 处理，不会无限「加载更多」。 */
    val hasMore: Boolean
        get() = items.size < total
}

/** 详情页与短剧评论面板共用的窄动作接口，不携带整页播放、收藏等行为。 */
data class CommentActions(
    val onExpand: () -> Unit,
    val onLoadMore: () -> Unit,
    val onRetry: () -> Unit,
    val onSubmit: (String) -> Unit,
)

/** 收起态先摆 3 条：详情页的主角是影片，评论只做预告。 */
internal const val COMMENT_PREVIEW_LIMIT = 3

/** 输入框与后端的上限对齐（`CommentController::postIndex` 截到 200 字，超出部分被服务端丢弃）。 */
internal const val COMMENT_MAX_LENGTH = 200

private const val FIRST_COMMENT_PAGE = 1
