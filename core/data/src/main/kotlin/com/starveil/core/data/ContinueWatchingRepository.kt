package com.starveil.core.data

import com.starveil.core.common.AppResult
import com.starveil.core.common.map
import com.starveil.core.model.ContinueWatching
import com.starveil.core.network.ProgressListApi
import com.starveil.core.network.dto.VideoProgressDto
import com.starveil.core.network.mapper.toDomain
import kotlinx.serialization.serializer

/**
 * 首页「继续观看」数据源（`GET v1.user/videoProgressList`）。
 *
 * [recent] 给默认实现（空列表）：其它 feature 的 fake 实现本接口时不必跟着补；
 * 真实实现见 [DefaultContinueWatchingRepository]。
 *
 * 未登录时后端返回 401 业务码，仓库照常返回失败，由调用方决定降级——
 * 首页的约定是"失败当没有记录"，不整页报错。
 */
interface ContinueWatchingRepository {

    suspend fun recent(): AppResult<List<ContinueWatching>> = AppResult.Success(emptyList())

    /**
     * 首帧快照：上一次成功拿到的观看记录。
     *
     * 默认没有——其它 feature 的测试 fake 不必为此补实现；[DefaultContinueWatchingRepository]
     * 返回注入进来的那一份。历史页构造时同步读它铺首屏，避免进来先整屏转圈。
     * 登录/登出会整目录清快照（`PageSnapshotStore.clearAll()`），所以 key 不拼 userId。
     */
    fun cachedHistory(): List<ContinueWatching>? = null

    /**
     * 某部影片的断点（最近看到哪个源 / 哪一集 / 第几秒）。
     *
     * 默认从 [recent] 里找，实现方不用重复写；服务端只回最近 10 条，找不到就当没看过。
     * 竖屏短剧走自己的 `VerticalPlayViewModel`，不经过这里。
     */
    suspend fun progressOf(vodId: Int): ContinueWatching? {
        val list = (recent() as? AppResult.Success)?.value.orEmpty()
        return list.firstOrNull { it.vodId == vodId }
    }
}

class DefaultContinueWatchingRepository(
    private val api: ProgressListApi,
    /** 首帧快照；为 null 时不落盘（测试与其它入口构造本类时不必提供）。 */
    private val snapshot: PageSnapshotStore? = null,
) : ContinueWatchingRepository {

    // 观看记录属于用户数据：不设年龄上限，过期只靠登录/登出清空
    override fun cachedHistory(): List<ContinueWatching>? =
        snapshot?.read(
            PageSnapshotStore.KEY_HISTORY,
            serializer<List<VideoProgressDto>>(),
            SnapshotStore.KEEP_FOREVER,
        )
            ?.map { dto -> dto.toDomain() }
            ?.takeIf { items -> items.isNotEmpty() }

    override suspend fun recent(): AppResult<List<ContinueWatching>> =
        apiCall { api.recent() }
            .requireData()
            .map { envelope ->
                saveSnapshot(envelope.list)
                envelope.list.map { it.toDomain() }
            }

    /**
     * 空列表要覆盖旧快照，不能跳过。
     *
     * 曾经写过"服务端回空多半是会话失效，留着旧快照给首帧用"——那条判断是错的：
     * 用户真把观看记录删光时，旧快照就永久留了下来，删掉的内容还会在首页继续出现。
     * 会话失效有登录态兜着，不靠快照兜。
     */
    private fun saveSnapshot(list: List<VideoProgressDto>) {
        if (list.isEmpty()) {
            snapshot?.remove(PageSnapshotStore.KEY_HISTORY)
            return
        }
        snapshot?.write(PageSnapshotStore.KEY_HISTORY, serializer<List<VideoProgressDto>>(), list)
    }
}
