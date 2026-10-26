package com.starveil.feature.topic

import com.starveil.core.common.AppResult
import com.starveil.core.data.TopicRepository
import com.starveil.core.model.Topic
import com.starveil.core.model.TopicDetail
import com.starveil.core.model.Vod

/**
 * 手写 fake，不引 mock 框架（AGENTS.md 第 7 节）。
 * 结果可在测试中途替换，用来模拟"刷新失败但已有列表"。
 */
internal class FakeTopicRepository(
    var listResult: AppResult<List<Topic>> = AppResult.Success(emptyList()),
    var detailResult: AppResult<TopicDetail> = AppResult.Success(topicDetail()),
) : TopicRepository {

    var listCalls: Int = 0
    var detailCalls: Int = 0

    /** 快照内容；默认没有快照，需要测首帧的用例自己塞。 */
    var cachedTopicsResult: List<Topic>? = null
    var cachedDetailResult: TopicDetail? = null

    override fun cachedTopics(): List<Topic>? = cachedTopicsResult

    override fun cachedTopicDetail(topicId: String): TopicDetail? = cachedDetailResult

    override suspend fun list(): AppResult<List<Topic>> {
        listCalls += 1
        return listResult
    }

    override suspend fun detail(topicId: String): AppResult<TopicDetail> {
        detailCalls += 1
        return detailResult
    }
}

internal fun topic(id: String = "1", name: String = "专题") = Topic(
    id = id,
    name = name,
    poster = null,
    blurb = null,
    content = null,
)

internal fun vod(id: Int = 1, name: String = "影片") = Vod(
    id = id,
    name = name,
    poster = null,
    remarks = null,
    year = null,
    area = null,
    score = null,
    blurb = null,
    playSources = emptyList(),
)

internal fun topicDetail(vods: List<Vod> = listOf(vod())) = TopicDetail(
    name = "专题",
    poster = null,
    blurb = null,
    content = null,
    tag = null,
    vods = vods,
)
