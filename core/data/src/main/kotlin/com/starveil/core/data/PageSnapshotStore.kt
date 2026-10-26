package com.starveil.core.data

import com.starveil.core.model.Vod
import com.starveil.core.network.dto.VodDto
import com.starveil.core.network.mapper.toDomain
import kotlinx.coroutines.Job
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer

/**
 * 列表页通用快照：一条 key 对应一份 JSON，键名由页面自己定（`rank_feed`、`live_feed`…）。
 *
 * 各页面的 ViewModel 只需要"上次的列表"，不需要知道磁盘、版本号、淘汰策略；
 * 那些都在 [SnapshotStore] 里。写入一律发生在仓库的成功分支：拿到 DTO 就地存原始响应，
 * 读取时再映射成领域模型，这样后端加字段不会让快照作废。
 *
 * ## 新增列表页 / 详情页的接入约定（三步，缺一步都等于没接）
 *
 * 1. 仓库成功分支存**原始 DTO**，失败分支不存——失败时旧快照比这次错误更有用。
 * 2. 仓库接口加 `fun cachedXxx(): T? = null`，**必须给默认实现**：其它模块手写的
 *    测试 fake 不会跟着改，接口加抽象方法会让它们全部编译失败（本项目踩过）。
 * 3. ViewModel 初值从 `cachedXxx()` 构造「已加载」状态；刷新时
 *    `isLoading = 当前没有内容`，不要无条件打回 Loading。
 *
 * ## 三条反例（review 重点看）
 *
 * - 在 ViewModel 里自己写文件：拿不到 Context，也绕过了版本号与登录清空。
 * - 把领域模型序列化存盘：领域模型不挂 `@Serializable`，也不该为此让 `:core:model`
 *   依赖序列化库；存 DTO 才对。
 * - 首帧用快照铺了内容、请求一开始又 `_state.value = Loading`：用户会看到内容
 *   闪一下再变回转圈，等于白接。
 */
class PageSnapshotStore(private val store: SnapshotStore) {

    /** 读一份影片列表；没存过、格式变化或超过 [maxAgeMs] 都返回 null。 */
    fun vods(key: String, maxAgeMs: Long = SnapshotStore.DEFAULT_MAX_AGE_MS): List<Vod>? =
        store.read(key, serializer<List<VodDto>>(), maxAgeMs)?.map { it.toDomain() }

    /** 空列表说明服务端已经没有内容：删掉旧快照，避免已下架的影片继续出现在首帧。 */
    fun saveVods(key: String, list: List<VodDto>) {
        if (list.isEmpty()) {
            store.remove(key)
            return
        }
        store.write(key, serializer<List<VodDto>>(), list)
    }

    /** 非影片结构的页面（榜单目录、专题详情、充值套餐…）用这两个泛型口。 */
    fun <T> read(
        key: String,
        deserializer: DeserializationStrategy<T>,
        maxAgeMs: Long = SnapshotStore.DEFAULT_MAX_AGE_MS,
    ): T? = store.read(key, deserializer, maxAgeMs)

    fun <T> write(key: String, serializer: SerializationStrategy<T>, value: T) {
        store.write(key, serializer, value)
    }

    /** 服务端明确回"没有内容"时用：这份键已经不可信了。 */
    fun remove(key: String) = store.remove(key)

    /** 一片一个文件的详情类快照拿来封顶：超过 [keep] 份就淘汰最旧的。 */
    fun prune(prefix: String, keep: Int) = store.prune(prefix, keep)

    /**
     * 清空全部快照并**等到磁盘真的删完**才返回。
     *
     * 登录/登出时调用：收藏、历史、观看进度这些带用户身份的数据不能跨账号复用，
     * 与其在每个 key 上拼 userId，不如整目录清一次——换账号后第一次进入慢一点，
     * 但绝不会出现"B 看到 A 的收藏"。
     */
    suspend fun clearAll() = store.clearAndAwait()

    /**
     * 同步作废代次 + 后台清目录，返回的 Job 可 join。
     *
     * 登出是点击回调，不能挂起，也不该用 `runBlocking` 卡住主线程；所以同步段只做
     * 必须立刻生效的一件事——推进代次。返回之后任何在途请求的写盘都会落空，
     * 目录什么时候删完都不再影响"不会写回旧账号数据"这条保证。
     */
    fun clearAllInBackground(): Job = store.clear()

    companion object {
        /** 列表页快照的键，集中在这里，避免各处字符串漂移。 */
        const val KEY_RANK_BOARD = "rank_board"
        const val KEY_RANK_CATALOG = "rank_catalog"
        const val KEY_LIVE = "live_feed"
        const val KEY_SHORT_PLAY = "shortplay_feed"
        const val KEY_TOPIC_LIST = "topic_list"
        const val KEY_TOPIC_DETAIL_PREFIX = "topic_"
        const val KEY_FAVORITE = "favorite_feed"
        const val KEY_HISTORY = "history_feed"
        const val KEY_PAY_PLANS = "pay_plans"
        const val KEY_POINTS = "points_feed"
        const val KEY_WITHDRAW = "withdraw_feed"
    }
}
