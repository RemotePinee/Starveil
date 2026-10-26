package com.starveil.feature.detail

import app.cash.turbine.test
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.data.AuthRepository
import com.starveil.core.data.COMMENT_PAGE_SIZE
import com.starveil.core.data.CommentRepository
import com.starveil.core.model.Comment
import com.starveil.core.model.CommentPage
import com.starveil.core.model.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 竖屏短剧的评论面板。
 *
 * 口径：进面板就展开并拉第一页、未登录发布走登录出口、发布成功重拉第一页（不伪造本地评论）、
 * 失败保留草稿；换号后旧账号的列表与发布回包整份丢弃，新账号的草稿与「已发布」不受影响。
 */
class CommentPanelViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `panel starts expanded and loads the first page`() = runTest {
        val comments = GatedCommentRepository()
        comments.nextPage = commentPage(total = 5, count = 3)

        val viewModel = createViewModel(comments)

        val state = viewModel.state.value
        assertTrue(state.isExpanded)
        assertEquals(3, state.items.size)
        assertEquals(5, state.total)
        assertFalse(state.isLoading)
        // 展开态不再藏条目：「查看全部」入口在这份面板里不会出现
        assertEquals(3, state.visibleItems.size)
        assertEquals(listOf(Triple(VOD_ID, 1, COMMENT_PAGE_SIZE)), comments.listCalls)
    }

    @Test
    fun `signed out submit asks for sign in without posting`() = runTest {
        val comments = GatedCommentRepository()
        val viewModel = createViewModel(comments, signedIn = false)

        viewModel.events.test {
            viewModel.submit("  第三集好看  ")
            assertEquals(CommentPanelEvent.SignInRequired, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, comments.postCalls)
        assertFalse(viewModel.state.value.posting)
        assertEquals(0, viewModel.state.value.postedCount)
    }

    @Test
    fun `successful submit refreshes page one and emits posted`() = runTest {
        val comments = GatedCommentRepository()
        val viewModel = createViewModel(comments)

        viewModel.events.test {
            viewModel.submit("  第三集好看  ")
            assertEquals(CommentPanelEvent.Posted, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(listOf(VOD_ID to "第三集好看"), comments.postedArgs)
        assertEquals(1, viewModel.state.value.postedCount)
        // 首屏 1 次 + 发布成功后刷新第一页 1 次：新评论的顺序以后端为准，不本地插一条
        assertEquals(
            listOf(
                Triple(VOD_ID, 1, COMMENT_PAGE_SIZE),
                Triple(VOD_ID, 1, COMMENT_PAGE_SIZE),
            ),
            comments.listCalls,
        )
    }

    @Test
    fun `rejected post keeps the draft counter and surfaces the business message`() = runTest {
        val comments = GatedCommentRepository()
        comments.postResult = AppResult.Failure(
            AppError.Business(code = 429, message = "评论太频繁，请稍后再试"),
        )
        val viewModel = createViewModel(comments)

        viewModel.events.test {
            viewModel.submit("第三集好看")
            assertEquals(CommentPanelEvent.Toast("评论太频繁，请稍后再试"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertFalse(viewModel.state.value.posting)
        assertEquals(0, viewModel.state.value.postedCount)
    }

    @Test
    fun `expired session on post asks for sign in`() = runTest {
        val comments = GatedCommentRepository()
        comments.postResult = AppResult.Failure(AppError.Http(code = 401, message = "Unauthorized"))
        val viewModel = createViewModel(comments)

        viewModel.events.test {
            viewModel.submit("第三集好看")
            assertEquals(CommentPanelEvent.SignInRequired, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, viewModel.state.value.postedCount)
    }

    @Test
    fun `stale account list reply never reaches the new account panel`() = runTest {
        val comments = GatedCommentRepository()
        val session = MutableStateFlow<Session?>(demoSession(userId = "42"))
        val gate = CompletableDeferred<Unit>()
        comments.listGate = gate
        val viewModel = createViewModel(comments, session = session)

        // 换号：宿主会用新账号新建一份 ViewModel，旧这份的迟到回包不能写进任何共享状态
        session.value = demoSession(userId = "77")
        gate.complete(Unit)

        val state = viewModel.state.value
        assertTrue(state.items.isEmpty())
        assertEquals(0, state.total)
        assertEquals(1, comments.listCalls.size)
    }

    @Test
    fun `stale account post reply does not clear the draft or report success`() = runTest {
        val comments = GatedCommentRepository()
        val session = MutableStateFlow<Session?>(demoSession(userId = "42"))
        val viewModel = createViewModel(comments, session = session)
        val gate = CompletableDeferred<Unit>()
        comments.postGate = gate

        viewModel.submit("旧账号写的草稿")
        session.value = demoSession(userId = "77")
        gate.complete(Unit)

        // 请求确实发出去了，但回包被整份丢弃：没有「已发布」，新账号的草稿因此不会被清空
        assertEquals(1, comments.postCalls)
        assertEquals(0, viewModel.state.value.postedCount)
    }

    @Test
    fun `input limit matches the server truncation`() {
        // 后端 `CommentController::postIndex` 截到 200 字；客户端不能在 300 字处放行
        assertEquals(200, COMMENT_MAX_LENGTH)
    }

    private fun createViewModel(
        comments: GatedCommentRepository,
        session: MutableStateFlow<Session?> = MutableStateFlow(demoSession()),
        signedIn: Boolean = true,
    ) = CommentPanelViewModel(
        deps = CommentPanelDeps(
            commentRepository = comments,
            authRepository = FakePanelAuthRepository(if (signedIn) demoSession() else null),
            session = session,
        ),
        vodId = VOD_ID,
    )
}

private const val VOD_ID = 7

private fun commentPage(
    total: Int,
    page: Int = 1,
    startId: Int = 100,
    count: Int = 3,
): CommentPage = CommentPage(
    items = (0 until count).map { offset ->
        Comment(
            id = startId + offset,
            content = "评论 ${startId + offset}",
            nickName = "用户$offset",
            createdAt = 1_729_300_000L - offset,
        )
    },
    total = total,
    page = page,
    limit = COMMENT_PAGE_SIZE,
)

private fun demoSession(userId: String = "42"): Session = Session(
    userId = userId,
    userName = "demo$userId",
    nickName = "演示用户",
    groupId = "1",
    groupName = "黄金会员",
    userCheck = "check-$userId",
    portrait = "",
)

/** 列表 / 发布各自可挂一道闸门：换号发生在回包之前，用来复现「迟到的旧账号回包」。 */
private class GatedCommentRepository : CommentRepository {

    var listGate: CompletableDeferred<Unit>? = null
    var postGate: CompletableDeferred<Unit>? = null
    var nextPage: CommentPage = commentPage(total = 0)
    var postResult: AppResult<Unit> = AppResult.Success(Unit)

    val listCalls = mutableListOf<Triple<Int, Int, Int>>()
    var postCalls: Int = 0
        private set
    var postedArgs: List<Pair<Int, String>> = emptyList()
        private set

    override suspend fun list(vodId: Int, page: Int, limit: Int): AppResult<CommentPage> {
        listCalls += Triple(vodId, page, limit)
        listGate?.await()
        return AppResult.Success(nextPage)
    }

    override suspend fun post(vodId: Int, content: String): AppResult<Unit> {
        postCalls++
        postedArgs = postedArgs + (vodId to content)
        postGate?.await()
        return postResult
    }
}

private class FakePanelAuthRepository(private val restored: Session?) : AuthRepository {

    override suspend fun login(userName: String, password: String): AppResult<Session> = unsupported()

    override suspend fun register(
        userName: String,
        password: String,
        passwordConfirm: String,
        uid: String,
    ): AppResult<Unit> = unsupported()

    override suspend fun sendRegisterCode(phone: String): AppResult<Unit> = unsupported()

    override suspend fun resetPassword(
        phone: String,
        password: String,
        passwordConfirm: String,
        code: String,
    ): AppResult<Unit> = unsupported()

    override suspend fun sendResetCode(phone: String): AppResult<Unit> = unsupported()

    override suspend fun restore(): Session? = restored

    override suspend fun logout() = Unit
}

private fun <T> unsupported(): AppResult<T> = AppResult.Failure(AppError.Unknown(cause = null))
