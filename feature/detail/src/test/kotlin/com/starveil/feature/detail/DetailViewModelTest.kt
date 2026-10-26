package com.starveil.feature.detail

import app.cash.turbine.test
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.data.AuthRepository
import com.starveil.core.data.FavoriteRepository
import com.starveil.core.data.VodRepository
import com.starveil.core.model.FavoriteItem
import com.starveil.core.model.PlayerInfo
import com.starveil.core.data.CommentRepository
import com.starveil.core.model.PlaySource
import com.starveil.core.model.PlayUrl
import com.starveil.core.model.Session
import com.starveil.core.model.Vod
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 详情页状态测试。
 *
 * 收藏口径是重点：判定靠第 1 页收藏列表里有没有本片
 * 未登录必须只发提示、不发请求，
 * 已收藏走 remove、未收藏走 add，不能凭本地状态反着调。
 */
class DetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `detail success exposes content and marks the vod as favorited`() = runTest {
        val favorites = FakeFavoriteRepository(listResult = AppResult.Success(listOf(favoriteItem(VOD_ID))))
        val viewModel = createViewModel(favorites = favorites, session = demoSession())

        viewModel.state.test {
            val content = awaitItem() as DetailUiState.Content
            assertEquals(VOD_ID, content.vod.id)
            assertEquals(listOf(0, 1), content.vod.playSources.map { it.index })
            assertEquals(0, content.sourceIndex)
            assertEquals(0, content.episodeIndex)
            assertEquals(DetailFavoriteState.Favorited, content.favorite)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, favorites.listCalls)
    }

    @Test
    fun `detail failure takes the error state and retry loads again`() = runTest {
        val vods = FakeVodRepository(detailResult = AppResult.Failure(AppError.Network(cause = null)))
        val viewModel = createViewModel(vods = vods)

        val failed = viewModel.state.value as DetailUiState.Error
        assertTrue(failed.message.contains("网络"))

        vods.detailResult = AppResult.Success(vod())
        viewModel.retry()

        val content = viewModel.state.value as DetailUiState.Content
        assertEquals(VOD_ID, content.vod.id)
    }

    @Test
    fun `vod without play sources shows the empty state`() = runTest {
        val vods = FakeVodRepository(
            detailResult = AppResult.Success(vod(playSources = listOf(source(0, episodes = emptyList())))),
        )
        val viewModel = createViewModel(vods = vods)

        val empty = viewModel.state.value as DetailUiState.Empty
        assertTrue(empty.message.isNotBlank())
    }

    @Test
    fun `signed out favorite tap asks for login without calling the api`() = runTest {
        val favorites = FakeFavoriteRepository()
        val viewModel = createViewModel(favorites = favorites, session = null)

        viewModel.events.test {
            viewModel.toggleFavorite()

            val toast = awaitItem() as DetailEvent.Toast
            assertEquals("请先登录", toast.message)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, favorites.listCalls)
        assertEquals(0, favorites.addCalls)
        assertEquals(0, favorites.removeCalls)
        assertEquals(DetailFavoriteState.SignedOut, contentOf(viewModel).favorite)
    }

    @Test
    fun `favorited vod is removed on tap`() = runTest {
        val favorites = FakeFavoriteRepository(listResult = AppResult.Success(listOf(favoriteItem(VOD_ID))))
        val viewModel = createViewModel(favorites = favorites, session = demoSession())
        assertEquals(DetailFavoriteState.Favorited, contentOf(viewModel).favorite)

        viewModel.toggleFavorite()

        assertEquals(1, favorites.removeCalls)
        assertEquals(listOf(VOD_ID), favorites.removedIds)
        assertEquals(0, favorites.addCalls)
        assertEquals(DetailFavoriteState.NotFavorited, contentOf(viewModel).favorite)
    }

    @Test
    fun `unfavorited vod is added on tap`() = runTest {
        val favorites = FakeFavoriteRepository(listResult = AppResult.Success(emptyList()))
        val viewModel = createViewModel(favorites = favorites, session = demoSession())
        assertEquals(DetailFavoriteState.NotFavorited, contentOf(viewModel).favorite)

        viewModel.toggleFavorite()

        assertEquals(1, favorites.addCalls)
        assertEquals(0, favorites.removeCalls)
        assertEquals(DetailFavoriteState.Favorited, contentOf(viewModel).favorite)
    }

    @Test
    fun `detail success loads same type recommendations`() = runTest {
        val vods = FakeVodRepository(
            detailResult = AppResult.Success(vod()),
            recommendResult = AppResult.Success(listOf(vod(id = 101), vod(id = 102))),
        )
        val viewModel = createViewModel(vods = vods)

        val content = contentOf(viewModel)
        assertEquals(listOf(101, 102), content.recommendations.map { it.id })
        assertEquals(1, vods.recommendCalls)
        assertEquals(3, vods.lastRecommendTypeId)
    }

    @Test
    fun `recommend failure keeps the detail visible`() = runTest {
        val vods = FakeVodRepository(
            detailResult = AppResult.Success(vod()),
            recommendResult = AppResult.Failure(AppError.Network(cause = null)),
        )
        val viewModel = createViewModel(vods = vods)

        val content = contentOf(viewModel)
        assertEquals(VOD_ID, content.vod.id)
        assertTrue(content.recommendations.isEmpty())
    }

    @Test
    fun `vod without type id never asks for recommendations`() = runTest {
        val vods = FakeVodRepository(detailResult = AppResult.Success(vod(typeId = 0)))
        val viewModel = createViewModel(vods = vods)

        assertEquals(0, vods.recommendCalls)
        assertTrue(contentOf(viewModel).recommendations.isEmpty())
    }

    private fun createViewModel(
        vods: FakeVodRepository = FakeVodRepository(AppResult.Success(vod())),
        favorites: FakeFavoriteRepository = FakeFavoriteRepository(),
        session: Session? = demoSession(),
        comments: CommentRepository = NoopCommentRepository,
    ) = DetailViewModel(
        deps = DetailDeps(
            vodRepository = vods,
            favoriteRepository = favorites,
            authRepository = FakeAuthRepository(session),
            commentRepository = comments,
        ),
        vodId = VOD_ID,
    )

    private fun contentOf(viewModel: DetailViewModel): DetailUiState.Content =
        viewModel.state.value as DetailUiState.Content
}

private const val VOD_ID = 7

private fun vod(
    id: Int = VOD_ID,
    typeId: Int = 3,
    playSources: List<PlaySource> = listOf(source(0, episodes = episodes()), source(1, episodes = episodes())),
): Vod = Vod(
    id = id,
    name = "测试影片",
    poster = null,
    remarks = "全 12 集",
    year = "2024",
    area = "大陆",
    score = "8.6",
    blurb = "一段简介",
    playSources = playSources,
    typeId = typeId,
)

private fun episodes(): List<PlayUrl> = listOf(
    PlayUrl(episodeNumber = 1, title = "第1集", url = "line-1"),
    PlayUrl(episodeNumber = 2, title = "第2集", url = "line-2"),
)

private fun source(index: Int, episodes: List<PlayUrl>): PlaySource = PlaySource(
    index = index,
    name = "线路$index",
    episodes = episodes,
    playerInfo = PlayerInfo(
        parseTargets = emptyList(),
        directLinkPatterns = emptyList(),
        headerPatterns = emptyList(),
        headers = emptyMap(),
    ),
)

private fun favoriteItem(vodId: Int): FavoriteItem = FavoriteItem(
    ulogId = vodId + 100,
    moduleId = 2,
    ulogType = 2,
    vodId = vodId,
    sid = 0,
    nid = 0,
    points = 0,
    createdAt = 0L,
    userName = null,
    title = "测试影片",
    poster = null,
    link = null,
    categoryName = null,
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

private class FakeVodRepository(
    var detailResult: AppResult<Vod>,
    var recommendResult: AppResult<List<Vod>> = AppResult.Success(emptyList()),
) : VodRepository {

    var recommendCalls: Int = 0
        private set

    var lastRecommendTypeId: Int = 0
        private set

    override suspend fun list(type: Int, page: Int, limit: Int): AppResult<List<Vod>> =
        AppResult.Failure(AppError.Unknown(cause = null))

    override suspend fun detail(vodId: Int): AppResult<Vod> = detailResult

    override suspend fun recommend(typeId: Int, limit: Int): AppResult<List<Vod>> {
        recommendCalls++
        lastRecommendTypeId = typeId
        return recommendResult
    }
}

private class FakeFavoriteRepository(
    var listResult: AppResult<List<FavoriteItem>> = AppResult.Success(emptyList()),
    private val addResult: AppResult<Unit> = AppResult.Success(Unit),
    private val removeResult: AppResult<Unit> = AppResult.Success(Unit),
) : FavoriteRepository {

    var listCalls: Int = 0
        private set

    var addCalls: Int = 0
        private set

    var removeCalls: Int = 0
        private set

    var removedIds: List<Int> = emptyList()
        private set

    override suspend fun list(page: Int, limit: Int): AppResult<List<FavoriteItem>> {
        listCalls++
        return listResult
    }

    override suspend fun add(vodId: Int): AppResult<Unit> {
        addCalls++
        return addResult
    }

    override suspend fun remove(vodIds: List<Int>): AppResult<Unit> {
        removeCalls++
        removedIds = vodIds
        return removeResult
    }
}

private class FakeAuthRepository(private val restored: Session?) : AuthRepository {

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

/** 详情测试不关心评论：用接口默认实现（空页），评论行为见 DetailCommentViewModelTest。 */
private object NoopCommentRepository : CommentRepository
