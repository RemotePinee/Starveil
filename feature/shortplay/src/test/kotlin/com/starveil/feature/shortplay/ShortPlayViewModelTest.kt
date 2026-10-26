package com.starveil.feature.shortplay

import app.cash.turbine.test
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.data.ShortPlayRepository
import com.starveil.core.model.ShortPlay
import com.starveil.core.model.ShortPlayType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.random.Random

class ShortPlayViewModelTest {
    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    @Test
    fun `first page and refresh use injected random source`() = runTest {
        val plays = (1..12).map(::shortPlay)
        val repository = ShortPlayTestRepository(pages = mutableMapOf(1 to AppResult.Success(plays)))
        val actualRandom = Random(37)
        val expectedRandom = Random(37)
        val viewModel = ShortPlayViewModel(repository, shuffle = { it.shuffled(actualRandom) })
        assertEquals(plays.shuffled(expectedRandom), viewModel.content().plays)
        assertEquals(1, viewModel.content().page)
        assertTrue(viewModel.content().endReached)
        assertNull(repository.calls.single().typeId)
        viewModel.refresh()
        assertEquals(plays.shuffled(expectedRandom), viewModel.content().plays)
        assertEquals(listOf(1, 1), repository.calls.map { it.page })
    }

    @Test
    fun `append shuffles and dedupes without moving anchor and stops at short page`() = runTest {
        val repository = ShortPlayTestRepository(pages = mutableMapOf(
            1 to AppResult.Success(listOf(shortPlay(1), shortPlay(2), shortPlay(2))),
            2 to AppResult.Success(listOf(shortPlay(2), shortPlay(3), shortPlay(4))),
            3 to AppResult.Success(emptyList()),
        ))
        val viewModel = ShortPlayViewModel(repository, pageSize = 3, shuffle = { it.reversed() })
        viewModel.onPageSettled(1)
        val version = viewModel.content().collectionVersion
        assertEquals(listOf(2, 1), viewModel.playIds())
        assertFalse(viewModel.content().endReached)
        viewModel.loadMore()
        assertEquals(listOf(2, 1, 4, 3), viewModel.playIds())
        assertEquals(1, viewModel.content().currentPlayId)
        assertEquals(version, viewModel.content().collectionVersion)
        viewModel.loadMore()
        viewModel.loadMore()
        assertTrue(viewModel.content().endReached)
        assertEquals(listOf(1, 2, 3), repository.calls.map { it.page })
    }

    @Test
    fun `append failure preserves anchor and retry requests next page`() = runTest {
        val repository = ShortPlayTestRepository(
            pages = mutableMapOf(1 to AppResult.Success(listOf(shortPlay(1), shortPlay(2)))),
        )
        val viewModel = stableShortPlayViewModel(repository)
        viewModel.onPageSettled(2)
        viewModel.loadMore()
        assertEquals(listOf(1, 2), viewModel.playIds())
        assertEquals(2, viewModel.content().currentPlayId)
        assertEquals(1, viewModel.content().page)
        assertNotNull(viewModel.content().appendError)
        repository.pages[2] = AppResult.Success(listOf(shortPlay(3)))
        viewModel.retry()
        assertEquals(listOf(1, 2, 3), viewModel.playIds())
        assertEquals(2, viewModel.content().currentPlayId)
        assertNull(viewModel.content().appendError)
        assertEquals(listOf(1, 2, 2), repository.calls.map { it.page })
    }

    @Test
    fun `category changes requery and retain backend types in empty and error states`() = runTest {
        var result: AppResult<List<ShortPlay>> = AppResult.Success(listOf(shortPlay(1), shortPlay(2)))
        val repository = ShortPlayTestRepository(response = { result })
        val types = shortPlayTypes()
        val viewModel = stableShortPlayViewModel(repository, types)
        result = AppResult.Success(emptyList())
        viewModel.selectType(22)
        assertTrue(viewModel.state.value is ShortPlayUiState.Empty)
        assertEquals(types, viewModel.state.value.types)
        assertEquals(22, viewModel.state.value.selectedTypeId)
        result = AppResult.Failure(AppError.Network(null))
        viewModel.selectType(21)
        assertTrue(viewModel.state.value is ShortPlayUiState.Error)
        assertEquals(types, viewModel.state.value.types)
        viewModel.selectType(999)
        assertEquals(listOf(21, 22, 21), repository.calls.map { it.typeId })
        assertEquals(listOf(1, 1, 1), repository.calls.map { it.page })
    }

    @Test
    fun `late first page cannot replace newly selected category`() = runTest {
        val pending = PendingShortPlayRepository()
        val viewModel = stableShortPlayViewModel(pending.repository, shortPlayTypes())
        viewModel.selectType(22)
        pending.succeed(1, 22)
        pending.succeed(0, 21)
        assertEquals(listOf(22), viewModel.playIds())
        assertEquals(22, viewModel.content().selectedTypeId)
    }

    @Test
    fun `late append failure cannot overwrite same category refresh generation`() = runTest {
        val pending = PendingShortPlayRepository()
        val viewModel = stableShortPlayViewModel(pending.repository)
        pending.succeed(0, 1, 2)
        viewModel.loadMore()
        viewModel.refresh()
        pending.succeed(2, 3, 4)
        pending.fail(1)
        assertEquals(listOf(3, 4), viewModel.playIds())
        assertNull(viewModel.content().appendError)
        assertFalse(viewModel.content().isLoadingMore)
        assertEquals(1, viewModel.content().page)
    }

    @Test
    fun `snapshot failure retains content and anchor until successful retry`() = runTest {
        val pending = CompletableDeferred<AppResult<List<ShortPlay>>>()
        var requests = 0
        val plays = listOf(shortPlay(1), shortPlay(2))
        val repository = ShortPlayTestRepository(snapshots = mapOf(null to plays), response = {
            if (++requests == 1) pending.await() else AppResult.Success(plays)
        })
        val viewModel = stableShortPlayViewModel(repository)
        viewModel.onPageSettled(2)
        viewModel.state.test {
            assertTrue((awaitItem() as ShortPlayUiState.Content).isRefreshing)
            pending.complete(AppResult.Failure(AppError.Network(null)))
            val failed = awaitItem() as ShortPlayUiState.Content
            assertEquals(plays, failed.plays)
            assertEquals(2, failed.currentPlayId)
            assertFalse(failed.isRefreshing)
            assertNotNull(failed.refreshError)
            viewModel.retry()
            assertEquals(2, viewModel.content().currentPlayId)
            assertNull(viewModel.content().refreshError)
            assertFalse(viewModel.content().isRefreshing)
            assertEquals(listOf(1, 1), repository.calls.map { it.page })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `snapshot revalidation preserves current id when shuffled position changes`() = runTest {
        val pending = CompletableDeferred<AppResult<List<ShortPlay>>>()
        val repository = ShortPlayTestRepository(
            snapshots = mapOf(null to listOf(shortPlay(1), shortPlay(2))), response = { pending.await() },
        )
        val viewModel = ShortPlayViewModel(repository, shuffle = { it.reversed() })
        viewModel.onPageSettled(1)
        viewModel.loadMore()
        assertEquals(1, repository.calls.size)
        pending.complete(AppResult.Success(listOf(shortPlay(3), shortPlay(2), shortPlay(1))))
        assertEquals(listOf(1, 2, 3), viewModel.playIds())
        assertEquals(1, viewModel.content().currentPlayId)
        assertFalse(viewModel.content().isRefreshing)
    }

    @Test
    fun `refresh keeps old anchor until successful replacement then resets to first play`() = runTest {
        val pending = CompletableDeferred<AppResult<List<ShortPlay>>>()
        var requests = 0
        val repository = ShortPlayTestRepository(response = {
            if (++requests == 1) AppResult.Success(listOf(shortPlay(1), shortPlay(2))) else pending.await()
        })
        val viewModel = stableShortPlayViewModel(repository)
        viewModel.onPageSettled(2)
        val version = viewModel.content().collectionVersion
        viewModel.refresh()
        assertEquals(2, viewModel.content().currentPlayId)
        assertEquals(version, viewModel.content().collectionVersion)
        pending.complete(AppResult.Success(listOf(shortPlay(3), shortPlay(4))))
        assertEquals(3, viewModel.content().currentPlayId)
        assertTrue(viewModel.content().collectionVersion > version)
    }

    @Test
    fun `pin drops in-flight first page and only appends in order`() = runTest {
        val firstPage = CompletableDeferred<AppResult<List<ShortPlay>>>()
        val repository = ShortPlayTestRepository(
            snapshots = mapOf(null to listOf(shortPlay(1), shortPlay(2))),
            response = { if (it.page == 1) firstPage.await() else AppResult.Success(listOf(shortPlay(3))) },
        )
        val viewModel = stableShortPlayViewModel(repository)
        viewModel.pinPlayback(2)
        assertFalse(viewModel.content().isRefreshing)
        firstPage.complete(AppResult.Success(listOf(shortPlay(9), shortPlay(8))))
        viewModel.loadMore()
        assertEquals(listOf(1, 2, 3), viewModel.playIds())
        assertEquals(2, viewModel.content().currentPlayId)
    }

    @Test
    fun `pinned feed ignores refresh and selectType until unpinned refresh`() = runTest {
        val repository = ShortPlayTestRepository(
            pages = mutableMapOf(1 to AppResult.Success(listOf(shortPlay(1), shortPlay(2)))),
        )
        val viewModel = stableShortPlayViewModel(repository, shortPlayTypes())
        viewModel.pinPlayback(1)
        viewModel.refresh()
        viewModel.selectType(22)
        assertEquals(1, repository.calls.size)
        assertEquals(21, viewModel.content().selectedTypeId)
        viewModel.pinPlayback(null)
        assertEquals(1, repository.calls.size)
        viewModel.refresh()
        assertEquals(2, repository.calls.size)
    }
}

private data class ShortPlayFeedCall(val typeId: Int?, val page: Int)

private class ShortPlayTestRepository(
    val pages: MutableMap<Int, AppResult<List<ShortPlay>>> = mutableMapOf(),
    private val snapshots: Map<Int?, List<ShortPlay>> = emptyMap(),
    private val response: (suspend (ShortPlayFeedCall) -> AppResult<List<ShortPlay>>)? = null,
) : ShortPlayRepository {
    val calls = mutableListOf<ShortPlayFeedCall>()

    override suspend fun feed(typeId: Int?, page: Int, limit: Int): AppResult<List<ShortPlay>> {
        val call = ShortPlayFeedCall(typeId, page)
        calls += call
        return response?.invoke(call) ?: pages[page] ?: AppResult.Failure(AppError.Unknown(null))
    }

    override fun cachedShortPlay(typeId: Int?): List<ShortPlay>? = snapshots[typeId]
}

/** 非协作挂起，模拟 cancel 后仍然返回的旧请求。 */
private class PendingShortPlayRepository {
    private val continuations = mutableListOf<Continuation<AppResult<List<ShortPlay>>>>()
    val repository = ShortPlayTestRepository(response = { suspendCoroutine { continuations += it } })

    fun succeed(index: Int, vararg ids: Int) {
        continuations[index].resume(AppResult.Success(ids.map(::shortPlay)))
    }

    fun fail(index: Int) {
        continuations[index].resume(AppResult.Failure(AppError.Network(null)))
    }
}

private fun stableShortPlayViewModel(
    repository: ShortPlayRepository,
    types: List<ShortPlayType> = emptyList(),
): ShortPlayViewModel = ShortPlayViewModel(repository, pageSize = 2, types = types, shuffle = { it })

private fun shortPlay(id: Int) = ShortPlay(
    id = id, title = "短剧$id", cover = null, episodeCount = 80,
    freeEpisodes = 0, category = "都市", blurb = null,
)

private fun ShortPlayViewModel.content(): ShortPlayUiState.Content = state.value as ShortPlayUiState.Content

private fun ShortPlayViewModel.playIds(): List<Int> = content().plays.map { it.id }

private fun shortPlayTypes() = listOf(ShortPlayType(21, "短剧"), ShortPlayType(22, "微短剧"))
