package com.starveil.core.data

import com.starveil.core.common.AppResult
import com.starveil.core.common.map
import com.starveil.core.common.onSuccess
import com.starveil.core.model.PointsSnapshot
import com.starveil.core.model.PointsStatus
import com.starveil.core.model.PointsTask
import com.starveil.core.model.ShareLink
import com.starveil.core.network.PointsApi
import com.starveil.core.network.dto.ApiEnvelopeDto
import com.starveil.core.network.dto.PointsSnapshotDto
import com.starveil.core.network.dto.PointsTaskDto
import com.starveil.core.network.dto.PointsTaskListDto
import com.starveil.core.network.dto.ScoreDto
import com.starveil.core.network.dto.SignStateDto
import kotlinx.serialization.serializer

/**
 * 积分中心：签到 + 今日任务。
 *
 * 后端口径（`server/starveilapi/src/Controller/UserController.php`）：
 * - `v1.user/sign` GET 查今天签没签，POST 签到并发放 `sign_points`
 * - `v1.user/task` GET 列今日任务，POST `key=watch` 领取"观看满 30 分钟"
 * - `v1.user/shareScore` POST 领分享奖励（每天首次有效）
 *
 * 积分本身存在 `mac_user.user_points`，买断单集时由服务端扣，客户端只负责展示与领取。
 */
interface PointsRepository {

    suspend fun status(): AppResult<PointsStatus>

    /** 签到，返回本次获得的积分（已经签过则 0）。 */
    suspend fun signIn(): AppResult<Int>

    suspend fun tasks(): AppResult<List<PointsTask>>

    /** 领取任务奖励，返回获得的积分。 */
    suspend fun claim(key: String): AppResult<Int>

    /** 上报分享并领取分享奖励，返回获得的积分。 */
    suspend fun shareScore(): AppResult<Int>

    /** 分享文案（标题/描述/链接），拉不到时 UI 用应用名兜底。 */
    suspend fun shareLink(): AppResult<ShareLink>

    /**
     * 上次成功响应的首帧快照（余额/签到 + 今日任务）。
     *
     * 给默认实现：其它模块手写的 fake 不必为快照额外实现一遍。
     */
    fun cachedPoints(): PointsSnapshot? = null
}

class DefaultPointsRepository(
    private val api: PointsApi,
    /**
     * 积分页首帧快照；为 null 时不落盘（测试与其它入口构造本类时不必提供）。
     * 只写在成功分支——上一次的好数据比这次的错误更有用。
     */
    private val snapshot: PageSnapshotStore? = null,
) : PointsRepository {

    /**
     * 首帧：上次的"签到状态 + 今日任务"。
     *
     * 缺 sign 就当没有快照：没有余额的积分页和空页没区别，半个快照反而更误导。
     */
    override fun cachedPoints(): PointsSnapshot? {
        val dto = snapshot?.read(PageSnapshotStore.KEY_POINTS, serializer<PointsSnapshotDto>())
        val sign = dto?.sign
        // 单个 return：detekt 的 ReturnCount 上限是 2，两段提前返回会把积分页判死
        return if (dto == null || sign == null) {
            null
        } else {
            PointsSnapshot(
                status = sign.toStatus(),
                tasks = dto.tasks?.list.orEmpty().map { it.toTask() },
            )
        }
    }

    override suspend fun status(): AppResult<PointsStatus> =
        apiCall { api.signState() }
            .requireData()
            .onSuccess { dto -> saveSign(dto) }
            .map { dto -> dto.toStatus() }

    override suspend fun signIn(): AppResult<Int> = scoreOf { api.signIn() }

    override suspend fun tasks(): AppResult<List<PointsTask>> =
        apiCall { api.pointsTasks() }
            .requireData()
            .onSuccess { dto -> saveTasks(dto) }
            .map { dto -> dto.list.map { task -> task.toTask() } }

    override suspend fun claim(key: String): AppResult<Int> = scoreOf { api.claimPointsTask(key) }

    override suspend fun shareScore(): AppResult<Int> = scoreOf { api.shareScore() }

    override suspend fun shareLink(): AppResult<ShareLink> =
        apiCall { api.shareInfo() }
            .requireData()
            .map { ShareLink(url = it.url, title = it.title, description = it.description) }

    private fun saveSign(dto: SignStateDto) = mergeSnapshot { it.copy(sign = dto) }

    private fun saveTasks(dto: PointsTaskListDto) = mergeSnapshot { it.copy(tasks = dto) }

    /**
     * sign 与 task 是两个接口，各写各的一半：整份覆盖会把另一半清掉，所以先读旧快照再合并。
     * 一份快照只对应一个 key，读完再写，页面不会看到"只有一半"的中间态。
     */
    private fun mergeSnapshot(update: (PointsSnapshotDto) -> PointsSnapshotDto) {
        val store = snapshot ?: return
        val previous = store.read(PageSnapshotStore.KEY_POINTS, serializer<PointsSnapshotDto>())
            ?: PointsSnapshotDto()
        store.write(PageSnapshotStore.KEY_POINTS, serializer<PointsSnapshotDto>(), update(previous))
    }

    private suspend fun scoreOf(
        call: suspend () -> ApiEnvelopeDto<ScoreDto>,
    ): AppResult<Int> = apiCall { call() }.requireData().map { it.score.toIntOrNull() ?: 0 }
}

/** 签到状态 DTO -> 领域模型。`points` 是余额，`sign_points` 才是签到能拿的分。 */
private fun SignStateDto.toStatus(): PointsStatus =
    PointsStatus(
        points = points,
        signedToday = signed == 1,
        // 老后端只有 points（那时它存的是签到分），退回它至少让按钮文案是对的
        signPoints = if (signPoints > 0) signPoints else points,
    )

/** 任务 DTO -> 领域模型；`done == 1` 表示今日做完且还能领，`claimed == 1` 表示今天已经领过。 */
private fun PointsTaskDto.toTask(): PointsTask =
    PointsTask(
        key = key,
        title = title,
        points = points,
        done = done == 1,
        target = target.coerceAtLeast(1),
        progress = progress,
        claimed = claimed == 1,
    )
