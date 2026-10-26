package com.starveil.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.core.data.AuthRepository
import com.starveil.core.data.CommentRepository
import com.starveil.core.model.Session
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** 评论面板的仓库依赖。收成一个对象，构造参数才不会被 detekt `LongParameterList` 拦下。 */
data class CommentPanelDeps(
    val commentRepository: CommentRepository,
    val authRepository: AuthRepository,
    /** 当前账号流：面板按创建那一瞬的身份隔离，换号后旧请求的回包一律丢弃。 */
    val session: StateFlow<Session?>,
)

/** 评论面板的一次性事件（AGENTS 第 5 节：一次性事件不进 UiState）。 */
sealed interface CommentPanelEvent {

    /** 可读提示，宿主用 Toast 展示。 */
    data class Toast(val message: String) : CommentPanelEvent

    /** 发布成功：输入框靠 [CommentSectionState.postedCount] 自己清草稿，这里只提示。 */
    data object Posted : CommentPanelEvent

    /** 未登录：宿主弹登录层（`LoginReason.COMMENT`），面板本身不持有登录入口。 */
    data object SignInRequired : CommentPanelEvent
}

/**
 * 评论面板的状态持有者（竖屏短剧的评论抽屉）。
 *
 * 与详情页共用 [DetailCommentController]：「拉第一页 / 加载更多 / 发布冷却 / 失败保留草稿」
 * 的口径只有一份，这里只多两件事：
 *
 * 1. **初始展开**：抽屉没有「收起」这一态，进来就要看到已加载的评论与底部分页入口。
 * 2. **账号隔离**：ViewModel 由宿主按账号做 key，这里再在每次回包时比对
 *    [CommentPanelDeps.session] 与创建时的身份。换号后旧账号的迟到回包既不写列表，
 *    也不清新账号的草稿、不吐「已发布」（否则用户在新账号里刚打的字会凭空消失）。
 *
 * 面板只渲染 [state]，动作与事件都由宿主接走：发布、重试、加载更多。
 */
class CommentPanelViewModel(
    deps: CommentPanelDeps,
    vodId: Int,
) : ViewModel() {

    /** 创建这份状态时的账号身份；之后每次回包都与当前会话比对。 */
    private val identity: Session? = deps.session.value

    private val _events = Channel<CommentPanelEvent>(Channel.BUFFERED)
    val events: Flow<CommentPanelEvent> = _events.receiveAsFlow()

    private val controller = DetailCommentController(
        context = CommentContext(
            repository = deps.commentRepository,
            authRepository = deps.authRepository,
            vodId = vodId,
            isCurrent = { deps.session.value == identity },
        ),
        scope = viewModelScope,
        callbacks = CommentCallbacks(
            notify = { message -> _events.trySend(CommentPanelEvent.Toast(message)) },
            onSignInRequired = { _events.trySend(CommentPanelEvent.SignInRequired) },
            onPosted = { _events.trySend(CommentPanelEvent.Posted) },
        ),
        expandedInitially = true,
    )

    /** 面板状态：已加载的评论、总数、分页与发布中标志。 */
    val state: StateFlow<CommentSectionState> = controller.state

    init {
        controller.loadFirstPage()
    }

    fun loadMore() = controller.loadMore()

    fun retry() = controller.loadFirstPage()

    fun toggleExpanded() = controller.toggleExpanded()

    /** 发布评论；空白内容与冷却中的连点由状态机自己丢弃。 */
    fun submit(content: String) = controller.submit(content)

    companion object {

        fun factory(deps: CommentPanelDeps, vodId: Int): ViewModelProvider.Factory = viewModelFactory {
            initializer { CommentPanelViewModel(deps = deps, vodId = vodId) }
        }
    }
}
