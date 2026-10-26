package com.starveil.feature.user

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.data.ContinueWatchingRepository
import com.starveil.core.model.ContinueWatching
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 观看记录页。三条：网络结果覆盖首帧快照、失败保留已有记录、没有快照才整页报错。
 *
 * 第三条是旧行为的回归点：以前 `refresh()` 一进来就置 `isLoading = true`，
 * 失败又把 `errorMessage` 填上，页面上原本能看的记录会被整屏错误页顶掉。
 */
class HistoryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `network result replaces the cached history`() = runTest {
        val repository = FakeHistoryRepository(
            result = AppResult.Success(listOf(historyItem(102))),
            cached = listOf(historyItem(101)),
        )

        val viewModel = HistoryViewModel(repository)

        assertEquals(listOf(102), viewModel.state.value.items.map { it.vodId })
        assertFalse(viewModel.state.value.isLoading)
    }

    @Test
    fun `failure keeps the cached history and shows no error page`() = runTest {
        val repository = FakeHistoryRepository(
            result = AppResult.Failure(AppError.Network(cause = null)),
            cached = listOf(historyItem(101)),
        )

        val viewModel = HistoryViewModel(repository)

        val state = viewModel.state.value
        assertEquals(listOf(101), state.items.map { it.vodId })
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `failure without a snapshot shows the error page`() = runTest {
        val viewModel = HistoryViewModel(
            FakeHistoryRepository(result = AppResult.Failure(AppError.Network(cause = null))),
        )

        assertNotNull(viewModel.state.value.errorMessage)
        assertTrue(viewModel.state.value.items.isEmpty())
    }
}

private class FakeHistoryRepository(
    private val result: AppResult<List<ContinueWatching>>,
    private val cached: List<ContinueWatching>? = null,
) : ContinueWatchingRepository {

    override fun cachedHistory(): List<ContinueWatching>? = cached

    override suspend fun recent(): AppResult<List<ContinueWatching>> = result
}

/** 一条观看记录样例，字段口径同 `DefaultContinueWatchingRepositoryTest`。 */
private fun historyItem(vodId: Int): ContinueWatching = ContinueWatching(
    vodId = vodId,
    vodName = "示例影片 $vodId",
    vodPic = "https://img.example.com/a.jpg",
    sourceIndex = 0,
    episodeIndex = 2,
    progressSec = 600,
    updatedAt = 1_760_000_000L,
)
