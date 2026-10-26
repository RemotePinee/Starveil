package com.starveil.app.ads

import com.tradplus.ads.open.reward.TPReward

/**
 * 激励请求要带的两样东西：用户标识（SSV 认人）、业务参数（回调里带回来）。
 *
 * 为什么只能是这两样：`trans_id` 由平台在自己的服务端回调里生成，客户端根本拿不到，
 * 所以客户端无法自证"我看完了"。它能做的只有——把"这次是谁"告诉平台，
 * 再让平台把业务参数原样回传；解锁由服务端回调写记录，客户端只负责事后重查闸门。
 */
data class AdRequestContext(
    val userId: String,
    val customData: String,
    /**
     * 这次要看广告解锁的位置。SSV 的 `custom_data` 为此拼成 `"$vodId:$episodeNumber"`，
     * 直投视频链路另要一份结构化值（`v1.ad/session` 的两个表单字段），
     * 所以两个字段各留一份，不靠解析字符串回推。
     */
    val vodId: Int = 0,
    val episodeNumber: Int = 0,
)

/**
 * 当前这一条激励请求的上下文，只有"看广告解锁某集"时才有值。
 *
 * 单独放一个进程级可变字段是迫不得已：SDK 的参数注入点在 provider 里（load 之前），
 * 而"解锁哪一集"只有播放页知道。为了不让它变成配置类里的一排 static 字段，
 * 只允许三件事：播放页点按钮时 [begin]、广告结束时 [clear]、provider load 时 [snapshot]。
 *
 * 线程安全：写入在主线程（点击），读取在广告 load（SDK 要求在构造/加载线程，实际也是主线程），
 * 中间夹着协程调度，所以用 `@Volatile` 保证可见性；单次引用赋值本身原子，不需要锁。
 */
object RewardRequestContext {

    @Volatile
    private var current: AdRequestContext? = null

    /**
     * 记下这次要看广告解锁的位置。
     *
     * `userId` 为空（游客 / 还没拿到会话）时不记：平台回调只能按账号写解锁记录，
     * `user_id=0` 的记录写进表也没人认，不如不写，让页面按"需登录"处理。
     */
    fun begin(userId: String?, vodId: Int, episodeNumber: Int) {
        current = if (incomplete(userId, vodId, episodeNumber)) {
            null
        } else {
            AdRequestContext(userId.orEmpty(), "$vodId:$episodeNumber", vodId, episodeNumber)
        }
    }

    /** 拆两级是为了不触发 detekt 的条件复杂度上限（3 就超）。 */
    private fun incomplete(userId: String?, vodId: Int, episodeNumber: Int): Boolean =
        userId.isNullOrBlank() || invalidTarget(vodId, episodeNumber)

    private fun invalidTarget(vodId: Int, episodeNumber: Int): Boolean =
        vodId <= 0 || episodeNumber <= 0

    /**
     * 广告结束（含中途关掉）后清掉。
     *
     * 清理时机放在回调里而不是 `finally`：展示失败时没有回调，值会留在原处；
     * 这无害——下一次 [begin] 一定覆盖它，provider 也只在 load 那一刻读一次。
     */
    fun clear() {
        current = null
    }

    fun snapshot(): AdRequestContext? = current
}

/**
 * 把 SSV 参数写进激励请求，并记住"这份缓存是在什么身份、哪个位下拉的"。
 *
 * SSV 的 key 是固定字符串（`user_id` / `custom_data`），没有常量类可用，
 * 所以收在一处，provider 里只留一行调用。
 *
 * 为什么需要"记住"：参数只在 load 那一刻进请求，而广告对象是按位复用的，
 * 换人/换集之后旧的缓存已经带不上新身份，直接展示等于让用户白看一条。
 */
internal class RewardSsvParams(private val contextProvider: () -> AdRequestContext?) {

    @Volatile
    private var applied: AdRequestContext? = null

    @Volatile
    private var appliedKey: String? = null

    /**
     * 标记"现在这份缓存属于当前身份"。
     *
     * @param key placementId / adUnitId，换位等于换对象，同样要重拉
     * @return true 表示缓存与当前身份一致，可以直接展示
     */
    fun prepare(key: String): Boolean {
        val same = appliedKey == key && applied == contextProvider()
        appliedKey = key
        applied = contextProvider()
        return same
    }

    /** TradPlus：key 是固定字符串 `user_id` / `custom_data`，没有常量类可用。 */
    fun applyTradPlus(ad: TPReward) {
        val request = contextProvider() ?: return
        ad.setCustomParams(
            mapOf("user_id" to request.userId, "custom_data" to request.customData),
        )
    }
}
