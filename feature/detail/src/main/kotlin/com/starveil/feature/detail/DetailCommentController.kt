package com.starveil.feature.detail

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.data.AuthRepository
import com.starveil.core.data.COMMENT_PAGE_SIZE
import com.starveil.core.data.CommentRepository
import com.starveil.core.model.CommentPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 评论区状态机，从 [DetailViewModel] 里拆出来单独一个类。
 *
 * 拆的理由是规模（AGENTS 第 2 节：单类 200 行）：详情页已经背着收藏、选集、
 * 推荐三块状态，评论再塞进去就超限。ViewModel 只保留同名委托方法，
 * UI 仍然只跟 ViewModel 打交道。竖屏短剧的评论抽屉复用同一个状态机
 * （[CommentPanelViewModel]），拉列表、分页去重、发布冷却、失败保留草稿的口径只有这一份。
 *
 * 四条口径：
 * 1. 首屏失败给 `error` 与重试；追加页失败只弹提示，已加载的评论不丢。
 * 2. 未登录点发送只提示、不发请求（后端会回 401，白跑一次还把提示显示成网络问题）。
 * 3. 发布成功后刷新第一页：新评论按 `comment_id desc` 排在最前，本地插入容易与后端顺序
 *    不一致，也不能凭空造一条「审核中」的评论。
 * 4. 换号后的迟到回包整份丢弃：不写列表、不清草稿、不吐「已发布」。
 */
internal data class CommentCallbacks(
    val notify: (String) -> Unit,
    /**
     * 未登录（本地没有会话或后端 401）的出口。
     *
     * 详情页把它映射成「请先登录」Toast；竖屏评论抽屉借它弹登录层（`LoginReason.COMMENT`）。
     */
    val onSignInRequired: () -> Unit = {},
    val onPosted: () -> Unit,
)

internal data class CommentContext(
    val repository: CommentRepository,
    val authRepository: AuthRepository,
    val vodId: Int,
    /**
     * 这次请求的账号身份是否仍是当前账号。
     *
     * 默认恒为真：详情页的 ViewModel 跟着页面走。评论抽屉按账号做 ViewModel key，
     * 旧账号的回包必须整份丢弃——既不能写进新账号的列表，也不能清掉新账号的草稿。
     */
    val isCurrent: () -> Boolean = { true },
)

/**
 * @param expandedInitially 进面板就展开（评论抽屉没有收起态）；详情页沿用收起态先摆 3 条。
 */
internal class DetailCommentController(
    private val context: CommentContext,
    private val scope: CoroutineScope,
    private val callbacks: CommentCallbacks,
    expandedInitially: Boolean = false,
) {

    private val _state = MutableStateFlow(CommentSectionState(isExpanded = expandedInitially))
    val state: StateFlow<CommentSectionState> = _state.asStateFlow()

    /** 拉第一页。进页面与「重试」都走这里；在飞时忽略重复调用。 */
    fun loadFirstPage() {
        if (_state.value.isLoading) return
        _state.update { it.copy(isLoading = true, error = null) }
        scope.launch { fetch(page = FIRST_PAGE, append = false) }
    }

    /** 追加下一页。收起态只有「加载更多」能触发，展开态见 [toggleExpanded]。 */
    fun loadMore() {
        val current = _state.value
        if (current.isLoading || current.isLoadingMore) return
        if (!current.hasMore) return
        _state.update { it.copy(isLoadingMore = true) }
        scope.launch { fetch(page = current.page + 1, append = true) }
    }

    /** 「查看全部 N 条」/「收起」：只改渲染范围，不跳路由；展开时顺手补下一页。 */
    fun toggleExpanded() {
        val expanded = !_state.value.isExpanded
        _state.update { it.copy(isExpanded = expanded) }
        if (expanded) loadMore()
    }

    /**
     * 发布评论。
     *
     * 空白内容直接丢弃（后端会回 400「参数不完整」）；登录判定放在请求之前，
     * 与收藏按钮同一口径。失败时保留输入框草稿，用户不用重打。
     */
    fun submit(content: String) {
        val text = content.trim()
        if (text.isEmpty() || _state.value.posting) return
        _state.update { it.copy(posting = true) }
        scope.launch { post(text) }
    }

    private suspend fun post(text: String) {
        if (!context.isCurrent()) return
        if (context.authRepository.restore() == null) {
            _state.update { it.copy(posting = false) }
            callbacks.onSignInRequired()
            return
        }
        if (context.isCurrent()) {
            val result = context.repository.post(context.vodId, text)
            // restore 与 post 都可能挂起；只有同一身份才能继续发送、接收结果。
            if (context.isCurrent()) applyPostResult(result)
        }
    }

    private suspend fun applyPostResult(result: AppResult<Unit>) {
        when (result) {
            is AppResult.Success -> applyPosted()
            is AppResult.Failure -> {
                _state.update { it.copy(posting = false) }
                // 后端 401 说明会话过期：与「本地没有会话」走同一个登录出口，不显示成网络错误。
                if (result.error.isSignInRequired()) callbacks.onSignInRequired()
                else callbacks.notify(result.error.toDetailMessage())
            }
        }
    }

    private suspend fun applyPosted() {
        _state.update { it.copy(posting = false, postedCount = it.postedCount + 1) }
        callbacks.onPosted()
        // 刷新第一页：失败也不回滚「已发布」，只把列表留在旧位置，用户可以下拉重试。
        fetch(page = FIRST_PAGE, append = false)
    }

    private suspend fun fetch(page: Int, append: Boolean) {
        if (!context.isCurrent()) return
        val result = context.repository.list(context.vodId, page, COMMENT_PAGE_SIZE)
        // 旧账号的列表回包不覆盖当前面板：列表、页码与错误态一律不动。
        if (context.isCurrent()) applyList(result, append)
    }

    private fun applyList(result: AppResult<CommentPage>, append: Boolean) {
        when (result) {
            is AppResult.Success -> _state.update { it.applied(result.value, append) }
            is AppResult.Failure -> applyLoadFailure(result.error, append)
        }
    }

    private fun applyLoadFailure(error: AppError, append: Boolean) {
        _state.update {
            it.copy(
                isLoading = false,
                isLoadingMore = false,
                // 追加页失败时保留首屏错误为空，只弹提示；否则重试按钮会把已加载内容藏起来。
                error = if (append) it.error else error.messageForUser(),
            )
        }
        if (append) callbacks.notify(error.messageForUser())
    }

    private fun CommentSectionState.applied(page: CommentPage, append: Boolean): CommentSectionState {
        val merged = if (append) (items + page.items).distinctBy { it.id } else page.items
        return copy(
            items = merged,
            page = page.page,
            // 后端 total 为 0（字段缺失）时退回已加载条数，避免「查看全部」入口消失后无法再翻页。
            total = maxOf(page.total, merged.size),
            isLoading = false,
            isLoadingMore = false,
            error = null,
        )
    }

    private fun AppError.messageForUser(): String =
        if (isSignInRequired()) SIGN_IN_REQUIRED else toDetailMessage()

    /** 未登录有两种落法：本地会话空（不发请求）与后端 401（会话过期）。 */
    private fun AppError.isSignInRequired(): Boolean = when (this) {
        is AppError.Business -> code == UNAUTHORIZED
        is AppError.Http -> code == UNAUTHORIZED
        else -> false
    }

    private companion object {
        const val FIRST_PAGE = 1
        const val UNAUTHORIZED = 401
        const val SIGN_IN_REQUIRED = "请先登录"
    }
}
