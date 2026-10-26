package com.starveil.feature.topic

import app.cash.turbine.test
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Rule
import org.junit.Test

/**
 * 专题列表状态测试：成功 / 空 / 失败三条主路径，
 * 外加"刷新失败保留旧列表并抛一次性事件"。
 */
class TopicViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    @Test
    fun `list success exposes topics`() {
        val fake = FakeTopicRepository(
            listResult = AppResult.Success(listOf(topic(id = "1"), topic(id = "2"))),
        )

        val state = TopicViewModel(fake).state.value as TopicListUiState.Content

        Assert.assertEquals(listOf("1", "2"), state.topics.map { it.id })
        Assert.assertFalse(state.isRefreshing)
    }

    @Test
    fun `empty list becomes empty state`() {
        val fake = FakeTopicRepository(listResult = AppResult.Success(emptyList()))

        val state = TopicViewModel(fake).state.value as TopicListUiState.Empty

        Assert.assertTrue(state.message.isNotBlank())
    }

    @Test
    fun `list failure becomes readable error`() {
        val fake = FakeTopicRepository(listResult = AppResult.Failure(AppError.Network(null)))

        val state = TopicViewModel(fake).state.value as TopicListUiState.Error

        Assert.assertTrue(state.message.contains("网络"))
    }

    @Test
    fun `cached topics are first frame and survive failure`() {
        val fake = FakeTopicRepository(listResult = AppResult.Failure(AppError.Network(null)))
        fake.cachedTopicsResult = listOf(topic(id = "9"))

        val state = TopicViewModel(fake).state.value as TopicListUiState.Content

        Assert.assertEquals(listOf("9"), state.topics.map { it.id })
        Assert.assertFalse(state.isRefreshing)
    }

    @Test
    fun `refresh failure keeps content and emits message`() = runTest {
        val fake = FakeTopicRepository(listResult = AppResult.Success(listOf(topic())))
        val viewModel = TopicViewModel(fake)
        fake.listResult = AppResult.Failure(AppError.Http(code = 500, message = "boom"))

        viewModel.events.test {
            viewModel.refresh()
            val event = awaitItem()
            Assert.assertTrue(event.text.contains("服务异常"))
            cancelAndIgnoreRemainingEvents()
        }

        val state = viewModel.state.value as TopicListUiState.Content
        Assert.assertEquals(1, state.topics.size)
        Assert.assertFalse(state.isRefreshing)
    }
}
