package com.starveil.feature.detail

import app.cash.turbine.test
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.data.AuthRepository
import com.starveil.core.data.COMMENT_PAGE_SIZE
import com.starveil.core.data.CommentRepository
import com.starveil.core.data.FavoriteRepository
import com.starveil.core.data.VodRepository
import com.starveil.core.model.Comment
import com.starveil.core.model.CommentPage
import com.starveil.core.model.FavoriteItem
import com.starveil.core.model.PlayerInfo
import com.starveil.core.model.PlaySource
import com.starveil.core.model.PlayUrl
import com.starveil.core.model.Session
import com.starveil.core.model.Vod
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 详情页评论状态。
 *
 * 口径：进页面随详情成功拉第一页、展开时才续拉下一页、未登录点发送只提示不发请求、
 * 发布成功后刷新第一页并抛一次性事件（页面据此弹 Toast 与清空草稿）。
 */
class DetailCommentViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `detail success loads the first comment page`() = runTest {
        val comments = FakeCommentRepository(nextPage = commentPage(total = 5))
        val viewModel = createViewModel(comments)

        val section = viewModel.comments.value
        assertFalse(section.isLoading)
        assertEquals(1, section.page)
        assertEquals(5, section.total)
        assertEquals(3, section.items.size)
        assertEquals(3, section.visibleItems.size)
        assertEquals(2, section.hiddenCount)
        assertEquals(3, section.total - section.hiddenCount)
        assertEquals(Triple(VOD_ID, 1, COMMENT_PAGE_SIZE), comments.lastListArgs)
    }

    @Test
    fun `expanding loads the next page and appends it`() = runTest {
        val comments = FakeCommentRepository(nextPage = commentPage(total = 5, page = 1, startId = 100))
        val viewModel = createViewModel(comments)
        comments.nextPage = commentPage(total = 5, page = 2, startId = 200, count = 2)

        viewModel.toggleCommentsExpanded()

        val section = viewModel.comments.value
        assertTrue(section.isExpanded)
        assertEquals(2, section.page)
        assertEquals(5, section.items.size)
        assertEquals(listOf(100, 101, 102, 200, 201), section.items.map { it.id })
        assertFalse(section.hasMore)
        // 收起态 3 条 + 展开后追加 2 条
        assertEquals(5, section.visibleItems.size)
    }

    @Test
    fun `list failure exposes a retryable error and keeps the detail visible`() = runTest {
        val comments = FakeCommentRepository(
            listResult = AppResult.Failure(AppError.Network(cause = null)),
        )
        val viewModel = createViewModel(comments)

        val failed = viewModel.comments.value
        assertFalse(failed.isLoading)
        assertTrue(requireNotNull(failed.error).contains("网络"))
        assertEquals(VOD_ID, (viewModel.state.value as DetailUiState.Content).vod.id)

        comments.listResult = AppResult.Success(commentPage(total = 1, count = 1))
        viewModel.loadComments()

        assertEquals(1, viewModel.comments.value.items.size)
        assertEquals(null, viewModel.comments.value.error)
    }

    @Test
    fun `signed out submit asks for login without posting`() = runTest {
        val comments = FakeCommentRepository()
        val viewModel = createViewModel(comments, session = null)

        viewModel.events.test {
            viewModel.submitComment("  第三集好看  ")
            assertEquals("请先登录", (awaitItem() as DetailEvent.Toast).message)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, comments.postCalls)
        assertFalse(viewModel.comments.value.posting)
    }

    @Test
    fun `successful submit posts trimmed text refreshes page one and emits the event`() = runTest {
        val comments = FakeCommentRepository(nextPage = commentPage(total = 2))
        val viewModel = createViewModel(comments)

        viewModel.events.test {
            viewModel.submitComment("  第三集好看  ")
            assertTrue(awaitItem() is DetailEvent.CommentPosted)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(listOf(VOD_ID to "第三集好看"), comments.postedArgs)
        assertEquals(1, viewModel.comments.value.postedCount)
        assertFalse(viewModel.comments.value.posting)
        // 首屏 1 次 + 发布成功后刷新第一页 1 次
        assertEquals(
            listOf(
                Triple(VOD_ID, 1, COMMENT_PAGE_SIZE),
                Triple(VOD_ID, 1, COMMENT_PAGE_SIZE),
            ),
            comments.listCalls,
        )
    }

    @Test
    fun `blank comment is dropped without a request`() = runTest {
        val comments = FakeCommentRepository()
        val viewModel = createViewModel(comments)

        viewModel.submitComment("   ")

        assertEquals(0, comments.postCalls)
        assertFalse(viewModel.comments.value.posting)
    }

    @Test
    fun `rejected post keeps the draft state clean and surfaces the business message`() = runTest {
        val comments = FakeCommentRepository(
            postResult = AppResult.Failure(AppError.Business(code = 429, message = "评论太频繁，请稍后再试")),
        )
        val viewModel = createViewModel(comments)

        viewModel.events.test {
            viewModel.submitComment("第三集好看")
            assertEquals("评论太频繁，请稍后再试", (awaitItem() as DetailEvent.Toast).message)
            cancelAndIgnoreRemainingEvents()
        }
        assertFalse(viewModel.comments.value.posting)
        assertEquals(0, viewModel.comments.value.postedCount)
    }

    private fun createViewModel(
        comments: FakeCommentRepository,
        session: Session? = demoSession(),
    ) = DetailViewModel(
        deps = DetailDeps(
            vodRepository = FakeDetailVodRepository(),
            favoriteRepository = EmptyFavoriteRepository(),
            authRepository = FakeCommentAuthRepository(session),
            commentRepository = comments,
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

private fun detailVod(): Vod = Vod(
    id = VOD_ID,
    name = "测试影片",
    poster = null,
    remarks = "全 2 集",
    year = "2024",
    area = "大陆",
    score = "8.6",
    blurb = "一段简介",
    playSources = listOf(
        PlaySource(
            index = 0,
            name = "线路0",
            episodes = listOf(PlayUrl(episodeNumber = 1, title = "第1集", url = "line-1")),
            playerInfo = PlayerInfo(
                parseTargets = emptyList(),
                directLinkPatterns = emptyList(),
                headerPatterns = emptyList(),
                headers = emptyMap(),
            ),
        ),
    ),
    typeId = 0,
)

private fun demoSession(): Session = Session(
    userId = "42",
    userName = "demo",
    nickName = "演示用户",
    groupId = "1",
    groupName = "黄金会员",
    userCheck = "abc123",
    portrait = "",
)

private class FakeCommentRepository(
    var nextPage: CommentPage = commentPage(total = 0),
    var listResult: AppResult<CommentPage>? = null,
    var postResult: AppResult<Unit> = AppResult.Success(Unit),
) : CommentRepository {

    val listCalls = mutableListOf<Triple<Int, Int, Int>>()
    var lastListArgs: Triple<Int, Int, Int>? = null
        private set

    var postCalls: Int = 0
        private set

    var postedArgs: List<Pair<Int, String>> = emptyList()
        private set

    override suspend fun list(vodId: Int, page: Int, limit: Int): AppResult<CommentPage> {
        val args = Triple(vodId, page, limit)
        listCalls += args
        lastListArgs = args
        return listResult ?: AppResult.Success(nextPage)
    }

    override suspend fun post(vodId: Int, content: String): AppResult<Unit> {
        postCalls++
        postedArgs = postedArgs + (vodId to content)
        return postResult
    }
}

private class FakeDetailVodRepository : VodRepository {

    override suspend fun list(type: Int, page: Int, limit: Int): AppResult<List<Vod>> =
        AppResult.Success(emptyList())

    override suspend fun detail(vodId: Int): AppResult<Vod> = AppResult.Success(detailVod())
}

private class EmptyFavoriteRepository : FavoriteRepository {

    override suspend fun list(page: Int, limit: Int): AppResult<List<FavoriteItem>> =
        AppResult.Success(emptyList())

    override suspend fun add(vodId: Int): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun remove(vodIds: List<Int>): AppResult<Unit> = AppResult.Success(Unit)
}

private class FakeCommentAuthRepository(private val restored: Session?) : AuthRepository {

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
