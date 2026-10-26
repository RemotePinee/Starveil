package com.starveil.core.data

import com.starveil.core.common.AppResult
import com.starveil.core.common.map
import com.starveil.core.model.Topic
import com.starveil.core.model.TopicDetail
import com.starveil.core.network.StarveilApi
import com.starveil.core.network.dto.TopicDetailDto
import com.starveil.core.network.dto.TopicDto
import com.starveil.core.network.mapper.toDomain
import kotlinx.serialization.serializer

/**
 * 专题（旧 与 ）。
 *
 * 列表和详情是两个不同路径、两种返回结构（分页壳 vs 单个对象），所以分开两个方法；
 * 详情里的影片不走分页，一次给全。
 */
interface TopicRepository {

    suspend fun list(): AppResult<List<Topic>>

    suspend fun detail(topicId: String): AppResult<TopicDetail>

    /**
     * 上次成功拉到的专题列表，供首帧直接铺内容；没有快照时为 null。
     *
     * 给默认实现是硬要求：手写 fake（`HomeTestDoubles`、`FakeTopicRepository`）不实现它也能编译。
     */
    fun cachedTopics(): List<Topic>? = null

    /**
     * 单个专题的详情快照；没有快照时为 null。
     *
     * id 是后端原样的字符串（`TopicDto.id`），不转 Int，与 [detail] 的入参一致。
     */
    fun cachedTopicDetail(topicId: String): TopicDetail? = null
}

class DefaultTopicRepository(
    private val api: StarveilApi,
    /**
     * 页面快照；为 null 时不落盘（测试与其它入口构造本类时不必提供）。
     * 写入点都在成功分支里：失败不覆盖快照——上一次的好数据比这次的错误更有用。
     */
    private val snapshot: PageSnapshotStore? = null,
) : TopicRepository {

    override suspend fun list(): AppResult<List<Topic>> =
        apiCall { api.topicList() }
            .requireData()
            .map { page ->
                // 专题全下线时要把旧快照删掉：留着它，冷启动还是上一次那排专题
                if (page.list.isEmpty()) {
                    snapshot?.remove(PageSnapshotStore.KEY_TOPIC_LIST)
                } else {
                    saveList(page.list)
                }
                page.list.map { dto -> dto.toDomain() }
            }

    override suspend fun detail(topicId: String): AppResult<TopicDetail> =
        apiCall { api.topicDetail(topicId) }
            .requireData()
            .map { dto ->
                saveDetail(topicId, dto)
                dto.toDomain()
            }

    override fun cachedTopics(): List<Topic>? =
        snapshot?.read(PageSnapshotStore.KEY_TOPIC_LIST, serializer<List<TopicDto>>())
            ?.map { dto -> dto.toDomain() }
            ?.takeIf { topics -> topics.isNotEmpty() }

    override fun cachedTopicDetail(topicId: String): TopicDetail? {
        val id = topicId.trim()
        if (id.isEmpty()) return null
        return snapshot?.read(detailKey(id), serializer<TopicDetailDto>())?.toDomain()
    }

    private fun saveList(list: List<TopicDto>) {
        snapshot?.write(PageSnapshotStore.KEY_TOPIC_LIST, serializer<List<TopicDto>>(), list)
    }

    private fun saveDetail(topicId: String, dto: TopicDetailDto) {
        val id = topicId.trim()
        // 参数为空时不能落盘：键会退化成公共前缀，把别人的详情覆盖掉。
        if (id.isEmpty()) return
        snapshot?.write(detailKey(id), serializer<TopicDetailDto>(), dto)
        // 一个专题一个文件：只留最近打开过的一批，被下架的专题靠这个上限被顶掉。
        // 以前只给 detail_ 前缀做过淘汰，topic_ 的会长到用户清数据为止。
        snapshot?.prune(PageSnapshotStore.KEY_TOPIC_DETAIL_PREFIX, KEEP_TOPIC_DETAILS)
    }

    private fun detailKey(id: String): String = PageSnapshotStore.KEY_TOPIC_DETAIL_PREFIX + id

    private companion object {
        /** 专题详情快照上限：与详情页同口径，只留最近 30 个。 */
        const val KEEP_TOPIC_DETAILS = 30
    }
}
