package com.starveil.feature.home

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.data.LivePage
import com.starveil.core.data.LiveRepository
import com.starveil.core.data.TopicRepository
import com.starveil.core.model.Topic
import com.starveil.core.model.TopicDetail

/**
 * 没有专题数据源时的兜底：首页只少一块横滑，不影响正文。
 *
 * 放在单独文件里是为了让 `HomeViewModel.kt` 留在 450 行上限内（AGENTS.md 第 2 节）。
 */
internal object EmptyTopicRepository : TopicRepository {

    override suspend fun list(): AppResult<List<Topic>> = AppResult.Success(emptyList())

    override suspend fun detail(topicId: String): AppResult<TopicDetail> =
        AppResult.Failure(AppError.Unknown(null))
}

/** 没有直播数据源时的兜底。 */
internal object EmptyLiveRepository : LiveRepository {

    override suspend fun list(page: Int, limit: Int, group: String): AppResult<LivePage> =
        AppResult.Success(
            LivePage(items = emptyList(), total = 0, groups = emptyList(), hasMore = false),
        )
}
