package com.starveil.feature.topic

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import org.junit.Assert
import org.junit.Rule
import org.junit.Test

/**
 * 专题详情状态测试：成功 / 参数缺失 / 失败三条主路径，
 * 外加"专题有效但没有影片"落到 Empty 且仍带头图数据。
 */
class TopicDetailViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    @Test
    fun `detail success exposes content`() {
        val fake = FakeTopicRepository(
            detailResult = AppResult.Success(topicDetail(vods = listOf(vod(id = 7)))),
        )

        val state = TopicDetailViewModel(fake, "3").state.value as TopicDetailUiState.Content

        Assert.assertEquals(7, state.detail.vods.single().id)
    }

    @Test
    fun `blank topic id becomes non retryable error without request`() {
        val fake = FakeTopicRepository()

        val state = TopicDetailViewModel(fake, "   ").state.value as TopicDetailUiState.Error

        Assert.assertFalse(state.retryable)
        Assert.assertTrue(state.message.isNotBlank())
        Assert.assertEquals(0, fake.detailCalls)
    }

    @Test
    fun `detail failure becomes readable error`() {
        val fake = FakeTopicRepository(detailResult = AppResult.Failure(AppError.Network(null)))

        val state = TopicDetailViewModel(fake, "3").state.value as TopicDetailUiState.Error

        Assert.assertTrue(state.retryable)
        Assert.assertTrue(state.message.contains("网络"))
    }

    @Test
    fun `cached detail is first frame and survives failure`() {
        val fake = FakeTopicRepository(detailResult = AppResult.Failure(AppError.Network(null)))
        fake.cachedDetailResult = topicDetail(vods = listOf(vod(id = 5)))

        val state = TopicDetailViewModel(fake, "3").state.value as TopicDetailUiState.Content

        Assert.assertEquals(5, state.detail.vods.single().id)
    }

    @Test
    fun `detail without vods becomes empty state`() {
        val fake = FakeTopicRepository(detailResult = AppResult.Success(topicDetail(vods = emptyList())))

        val state = TopicDetailViewModel(fake, "3").state.value as TopicDetailUiState.Empty

        Assert.assertEquals("专题", state.detail.name)
        Assert.assertTrue(state.message.isNotBlank())
    }
}
